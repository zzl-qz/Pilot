package com.pilot.server.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pilot.server.common.data.DataMaintenance;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.results.ResultInboxService;
import com.pilot.server.store.AutomationStore;

import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/** 持久 outbox；通知失败只重试投递，绝不触发模型执行。对外提供稳定幂等键。 */
@Service
@Slf4j
public class DeliveryService {
    private static final String DELIVERY_KIND = "delivery";
    private static final String SETTINGS_KIND = "delivery-settings";
    private static final String SETTINGS_ID = "default";
    /** 桌面渠道没有地址，目标栏写这个占位值。 */
    private static final String DESKTOP_TARGET = "desktop";

    /** 同一条通知最多投递这么多次，之后只能手动重试。 */
    private static final int MAX_ATTEMPTS = 6;
    /** 领取后的租约时长；超时未回执会被重新领取。 */
    private static final long LEASE_SECONDS = 45;
    /** 失败重试的退避：从 15 秒起每次翻倍，最多翻 8 次，封顶一小时。 */
    private static final long RETRY_BASE_SECONDS = 15;
    private static final int RETRY_MAX_DOUBLINGS = 8;
    private static final long RETRY_MAX_SECONDS = 3600;
    /** 同时发送的 Webhook 数。 */
    private static final int SEND_CONCURRENCY = 2;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_WEBHOOK_URL_CHARS = 2048;
    private static final int MAX_SUMMARY_CHARS = 1500;
    private static final int MAX_ERROR_CHARS = 300;
    private static final int LIST_LIMIT = 200;
    private static final String CHANNEL_CLOSED = "通知渠道已关闭或地址已改变";

    private DataMaintenance maintenance = new DataMaintenance();

    @org.springframework.beans.factory.annotation.Autowired
    void useMaintenance(PilotDataDir dataDir) {
        this.maintenance = dataDir.maintenance();
    }

    public record Settings(long version, boolean desktop, String webhookUrl, Instant desktopSince,
        Instant webhookSince) {
    }

    public record Delivery(String id, long version, String resultId, String revision, String channel, String target,
        String title, String summary, String state, int attempts, Instant nextAt, String lease, String error,
        Instant createdAt, String outcome) {
    }

    public record Receipt(String lease, boolean success, String error) {
    }

    @FunctionalInterface
    public interface Sender {
        int send(String target, String id, String payload) throws Exception;
    }

    private final AutomationStore store;
    private final ObjectMapper mapper;
    private final ResultInboxService results;
    private final Clock clock;
    private final Sender sender;
    private final Semaphore slots = new Semaphore(SEND_CONCURRENCY);
    private final ExecutorService executor =
        Executors.newFixedThreadPool(SEND_CONCURRENCY, Thread.ofVirtual().factory());

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryService(AutomationStore store, ObjectMapper mapper, ResultInboxService results) {
        this(store, mapper, results, Clock.systemUTC());
    }

    public DeliveryService(AutomationStore store, ObjectMapper mapper, ResultInboxService results, Clock clock) {
        this(store, mapper, results, clock, httpSender());
    }

    public DeliveryService(AutomationStore store, ObjectMapper mapper, ResultInboxService results, Clock clock,
        Sender sender) {
        this.store = store;
        this.mapper = mapper;
        this.results = results;
        this.clock = clock;
        this.sender = sender;
    }

    private static Sender httpSender() {
        var client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        return (target, id, body) -> client
            .send(HttpRequest.newBuilder(URI.create(target)).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json").header("Idempotency-Key", id)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
            .statusCode();
    }

    public Settings settings() {
        return store.get(SETTINGS_KIND, SETTINGS_ID).map(d -> mapper.readValue(d.payload(), Settings.class))
            .orElse(new Settings(0, false, "", null, null));
    }

    public synchronized Settings configure(long version, boolean desktop, String webhook) {
        var old = settings();
        if (old.version() != version) {
            throw new IllegalStateException("通知设置已变化，请刷新");
        }
        String url = webhook == null ? "" : webhook.strip();
        validateTarget(url);
        var next = new Settings(version + 1, desktop, url,
            desktop ? (old.desktop() ? old.desktopSince() : clock.instant()) : null,
            url.isBlank() ? null : url.equals(old.webhookUrl()) ? old.webhookSince() : clock.instant());
        if (!store.put(SETTINGS_KIND, SETTINGS_ID, version, mapper.writeValueAsString(next))) {
            throw new IllegalStateException("通知设置已变化，请刷新");
        }
        for (var d : candidates()) {
            if (!enabled(d, next) && !DeliveryState.SENDING.is(d.state)) {
                save(d, DeliveryState.CANCELLED, d.attempts, clock.instant(), "", CHANNEL_CLOSED);
            }
        }
        return next;
    }

    private static void validateTarget(String url) {
        if (url.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(url);
            if (url.length() > MAX_WEBHOOK_URL_CHARS || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || !Set.of("https", "http").contains(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Webhook 须为有效 HTTP(S) 地址，不能包含账号密码或片段");
        }
    }

    private List<Delivery> candidates() {
        return store.deliveryCandidates().stream().map(d -> mapper.readValue(d.payload(), Delivery.class))
            .filter(d -> DeliveryState.open(d.state)).toList();
    }

    public List<Delivery> list() {
        return store.latest(DELIVERY_KIND, LIST_LIMIT).stream().map(d -> mapper.readValue(d.payload(), Delivery.class))
            .sorted(Comparator.comparing(Delivery::createdAt).reversed()).limit(LIST_LIMIT)
            .map(d -> new Delivery(d.id, d.version, d.resultId, d.revision, d.channel, "", d.title, d.summary, d.state,
                d.attempts, d.nextAt, "", d.error, d.createdAt, d.outcome))
            .toList();
    }

    public Delivery get(String id) {
        return store.get(DELIVERY_KIND, id).map(d -> mapper.readValue(d.payload(), Delivery.class))
            .orElseThrow(() -> new IllegalArgumentException("投递记录不存在"));
    }

    public synchronized void collect() {
        var config = settings();
        if (!config.desktop() && config.webhookUrl().isBlank()) {
            return;
        }
        Instant since = config.desktopSince() == null ? config.webhookSince()
            : config.webhookSince() == null ? config.desktopSince()
                : config.desktopSince().isBefore(config.webhookSince()) ? config.desktopSince() : config.webhookSince();
        for (var item : results.deliveryItems(since)) {
            if (config.desktop() && !item.at().isBefore(config.desktopSince())) {
                enqueue(item, DeliveryChannel.DESKTOP, DESKTOP_TARGET);
            }
            if (!config.webhookUrl().isBlank() && !item.at().isBefore(config.webhookSince())) {
                enqueue(item, DeliveryChannel.WEBHOOK, config.webhookUrl());
            }
        }
    }

    private void enqueue(ResultInboxService.Item item, DeliveryChannel channel, String target) {
        String key = UUID.nameUUIDFromBytes(
            (item.id() + ":" + item.revision() + ":" + channel.name()).getBytes(StandardCharsets.UTF_8)).toString();
        String title = (ResultInboxService.needsAttention(item) ? "需要处理 · " : "任务结果 · ") + item.title();
        String summary = Objects.toString(item.summary(), "");
        summary = summary.substring(0, Math.min(summary.length(), MAX_SUMMARY_CHARS));
        var row = new Delivery(key, 1, item.id(), item.revision(), channel.name(), target, title, summary,
            DeliveryState.PENDING.name(), 0, clock.instant(), "", "", clock.instant(), item.state());
        store.put(DELIVERY_KIND, key, 0, mapper.writeValueAsString(row));
    }

    private boolean enabled(Delivery d, Settings c) {
        return DeliveryChannel.DESKTOP.is(d.channel) ? c.desktop()
            : !c.webhookUrl().isBlank() && c.webhookUrl().equals(d.target);
    }

    public synchronized Delivery claim(DeliveryChannel channel) {
        Instant now = clock.instant();
        var config = settings();
        for (var d : candidates().stream().sorted(Comparator.comparing(Delivery::createdAt)).toList()) {
            if (!channel.is(d.channel) || !DeliveryState.open(d.state) || d.nextAt.isAfter(now)) {
                continue;
            }
            if (!enabled(d, config)) {
                save(d, DeliveryState.CANCELLED, d.attempts, now, "", CHANNEL_CLOSED);
                continue;
            }
            if (d.attempts >= MAX_ATTEMPTS) {
                save(d, DeliveryState.FAILED, d.attempts, now, "", "已达到 " + MAX_ATTEMPTS + " 次投递上限，可手动重试");
                continue;
            }
            var claimed = save(d, DeliveryState.SENDING, d.attempts + 1, now.plusSeconds(LEASE_SECONDS),
                UUID.randomUUID().toString(), "");
            if (claimed != null) {
                return claimed;
            }
        }
        return null;
    }

    private Delivery save(Delivery d, DeliveryState state, int attempts, Instant at, String lease, String error) {
        var next = new Delivery(d.id, d.version + 1, d.resultId, d.revision, d.channel, d.target, d.title, d.summary,
            state.name(), attempts, at, lease, error, d.createdAt, d.outcome);
        return store.put(DELIVERY_KIND, d.id, d.version, mapper.writeValueAsString(next)) ? next : null;
    }

    public synchronized void receipt(String id, Receipt receipt) {
        var row = get(id);
        if (receipt == null || !validLease(row, receipt.lease())) {
            throw new IllegalStateException("投递租约已过期，请刷新");
        }
        boolean ok = receipt.success();
        DeliveryState state =
            ok ? DeliveryState.DELIVERED : row.attempts >= MAX_ATTEMPTS ? DeliveryState.FAILED : DeliveryState.RETRY;
        String error = ok ? "" : Objects.toString(receipt.error(), "投递失败");
        error = error.substring(0, Math.min(error.length(), MAX_ERROR_CHARS));
        if (save(row, state, row.attempts, clock.instant().plusSeconds(ok ? 0 : retryDelaySeconds(row.attempts)), "",
            error) == null) {
            throw new IllegalStateException("投递状态已变化");
        }
    }

    private static long retryDelaySeconds(int attempts) {
        return Math.min(RETRY_MAX_SECONDS, RETRY_BASE_SECONDS << Math.min(attempts, RETRY_MAX_DOUBLINGS));
    }

    public synchronized void retry(String id, long version) {
        var d = get(id);
        if (d.version != version) {
            throw new IllegalStateException("投递状态已变化，请刷新");
        }
        if (!DeliveryState.retryable(d.state)) {
            throw new IllegalArgumentException("只有失败的投递可以重试");
        }
        if (!enabled(d, settings())) {
            throw new IllegalArgumentException("通知渠道已关闭或地址已变化，请检查设置");
        }
        if (save(d, DeliveryState.PENDING, 0, clock.instant(), "", "") == null) {
            throw new IllegalStateException("投递状态已变化");
        }
    }

    public void send(Delivery d) {
        try {
            if (!prepareSend(d)) {
                return;
            }
            int status =
                sender.send(d.target, d.id, mapper.writeValueAsString(Map.of("id", d.id, "resultId", d.resultId,
                    "title", d.title, "summary", d.summary, "state", d.outcome, "createdAt", d.createdAt.toString())));
            boolean accepted = status >= 200 && status < 300;
            receipt(d.id, new Receipt(d.lease, accepted, accepted ? "" : "Webhook 返回 HTTP " + status));
        } catch (Exception e) {
            try {
                receipt(d.id, new Receipt(d.lease, false, "Webhook 连接失败或超时，请检查地址和接收服务"));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean validLease(Delivery row, String lease) {
        return DeliveryState.SENDING.is(row.state) && row.lease.equals(lease) && row.nextAt.isAfter(clock.instant());
    }

    /** 领取后可能等待维护锁；实际发送前重新核对租约和渠道，不能使用过期的排队快照。 */
    private synchronized boolean prepareSend(Delivery claimed) {
        var row = get(claimed.id);
        if (!DeliveryChannel.WEBHOOK.is(row.channel) || !validLease(row, claimed.lease)) {
            return false;
        }
        if (!enabled(row, settings())) {
            save(row, DeliveryState.CANCELLED, row.attempts, clock.instant(), "", CHANNEL_CLOSED);
            return false;
        }
        return true;
    }

    @Scheduled(fixedDelayString = "${pilot.notifications.poll-ms:5000}")
    public void poll() {
        maintenance.whenAvailable(this::pollDeliveries);
    }

    private void pollDeliveries() {
        try {
            collect();
            while (slots.tryAcquire()) {
                Delivery d;
                try {
                    d = claim(DeliveryChannel.WEBHOOK);
                } catch (RuntimeException e) {
                    slots.release();
                    throw e;
                }
                if (d == null) {
                    slots.release();
                    break;
                }
                try {
                    executor.submit(() -> {
                        try (var activity = maintenance.activity()) {
                            send(d);
                        } finally {
                            slots.release();
                        }
                    });
                } catch (RuntimeException e) {
                    slots.release();
                    throw e;
                }
            }
        } catch (RuntimeException e) {
            log.warn("通知投递暂不可用，稍后重试", e);
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
