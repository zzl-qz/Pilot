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

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.maintenance.DataMaintenance;
import com.pilot.server.results.ResultInboxService;
import com.pilot.server.store.AutomationStore;

import tools.jackson.databind.ObjectMapper;

/** 持久 outbox；通知失败只重试投递，绝不触发模型执行。对外提供稳定幂等键。 */
@Service
public class DeliveryService {
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
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory());

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
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return (target, id, body) -> client
            .send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Idempotency-Key", id)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
            .statusCode();
    }

    public Settings settings() {
        return store.get("delivery-settings", "default").map(d -> mapper.readValue(d.payload(), Settings.class))
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
        if (!store.put("delivery-settings", "default", version, mapper.writeValueAsString(next))) {
            throw new IllegalStateException("通知设置已变化，请刷新");
        }
        for (var d : candidates()) {
            if (!enabled(d, next) && !"SENDING".equals(d.state)) {
                save(d, "CANCELLED", d.attempts, clock.instant(), "", "通知渠道已关闭或地址已改变");
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
            if (url.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !Set.of("https", "http").contains(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Webhook 须为有效 HTTP(S) 地址，不能包含账号密码或片段");
        }
    }

    private List<Delivery> candidates() {
        return store.deliveryCandidates().stream().map(d -> mapper.readValue(d.payload(), Delivery.class))
            .filter(d -> Set.of("PENDING", "RETRY", "SENDING").contains(d.state)).toList();
    }

    public List<Delivery> list() {
        return store.latest("delivery", 200).stream().map(d -> mapper.readValue(d.payload(), Delivery.class))
            .sorted(Comparator.comparing(Delivery::createdAt).reversed()).limit(200)
            .map(d -> new Delivery(d.id, d.version, d.resultId, d.revision, d.channel, "", d.title, d.summary, d.state,
                d.attempts, d.nextAt, "", d.error, d.createdAt, d.outcome))
            .toList();
    }

    public Delivery get(String id) {
        return store.get("delivery", id).map(d -> mapper.readValue(d.payload(), Delivery.class))
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
                enqueue(item, "DESKTOP", "desktop");
            }
            if (!config.webhookUrl().isBlank() && !item.at().isBefore(config.webhookSince())) {
                enqueue(item, "WEBHOOK", config.webhookUrl());
            }
        }
    }

    private void enqueue(ResultInboxService.Item item, String channel, String target) {
        String key =
            UUID.nameUUIDFromBytes((item.id() + ":" + item.revision() + ":" + channel).getBytes(StandardCharsets.UTF_8))
                .toString();
        String title = ("NEEDS_ATTENTION".equals(item.state()) ? "需要处理 · " : "任务结果 · ") + item.title();
        String summary = Objects.toString(item.summary(), "");
        summary = summary.substring(0, Math.min(summary.length(), 1500));
        var row = new Delivery(key, 1, item.id(), item.revision(), channel, target, title, summary, "PENDING", 0,
            clock.instant(), "", "", clock.instant(), item.state());
        store.put("delivery", key, 0, mapper.writeValueAsString(row));
    }

    private boolean enabled(Delivery d, Settings c) {
        return d.channel.equals("DESKTOP") ? c.desktop() : !c.webhookUrl().isBlank() && c.webhookUrl().equals(d.target);
    }

    public synchronized Delivery claim(String channel) {
        Instant now = clock.instant();
        var config = settings();
        for (var d : candidates().stream().sorted(Comparator.comparing(Delivery::createdAt)).toList()) {
            if (!d.channel.equals(channel) || !Set.of("PENDING", "RETRY", "SENDING").contains(d.state)
                || d.nextAt.isAfter(now)) {
                continue;
            }
            if (!enabled(d, config)) {
                save(d, "CANCELLED", d.attempts, now, "", "通知渠道已关闭或地址已改变");
                continue;
            }
            if (d.attempts >= 6) {
                save(d, "FAILED", d.attempts, now, "", "已达到 6 次投递上限，可手动重试");
                continue;
            }
            var claimed = save(d, "SENDING", d.attempts + 1, now.plusSeconds(45), UUID.randomUUID().toString(), "");
            if (claimed != null) {
                return claimed;
            }
        }
        return null;
    }

    private Delivery save(Delivery d, String state, int attempts, Instant at, String lease, String error) {
        var next = new Delivery(d.id, d.version + 1, d.resultId, d.revision, d.channel, d.target, d.title, d.summary,
            state, attempts, at, lease, error, d.createdAt, d.outcome);
        return store.put("delivery", d.id, d.version, mapper.writeValueAsString(next)) ? next : null;
    }

    public synchronized void receipt(String id, Receipt receipt) {
        var row = get(id);
        if (receipt == null || !validLease(row, receipt.lease())) {
            throw new IllegalStateException("投递租约已过期，请刷新");
        }
        boolean ok = receipt.success();
        String state = ok ? "DELIVERED" : row.attempts >= 6 ? "FAILED" : "RETRY";
        String error = ok ? "" : Objects.toString(receipt.error(), "投递失败");
        error = error.substring(0, Math.min(error.length(), 300));
        if (save(row, state, row.attempts,
            clock.instant().plusSeconds(ok ? 0 : Math.min(3600, 15L << Math.min(row.attempts, 8))), "",
            error) == null) {
            throw new IllegalStateException("投递状态已变化");
        }
    }

    public synchronized void retry(String id, long version) {
        var d = get(id);
        if (d.version != version) {
            throw new IllegalStateException("投递状态已变化，请刷新");
        }
        if (!Set.of("FAILED", "RETRY").contains(d.state)) {
            throw new IllegalArgumentException("只有失败的投递可以重试");
        }
        if (!enabled(d, settings())) {
            throw new IllegalArgumentException("通知渠道已关闭或地址已变化，请检查设置");
        }
        if (save(d, "PENDING", 0, clock.instant(), "", "") == null) {
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
            receipt(d.id, new Receipt(d.lease, status >= 200 && status < 300,
                status >= 200 && status < 300 ? "" : "Webhook 返回 HTTP " + status));
        } catch (Exception e) {
            try {
                receipt(d.id, new Receipt(d.lease, false, "Webhook 连接失败或超时，请检查地址和接收服务"));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean validLease(Delivery row, String lease) {
        return "SENDING".equals(row.state) && row.lease.equals(lease) && row.nextAt.isAfter(clock.instant());
    }

    /** 领取后可能等待维护锁；实际发送前重新核对租约和渠道，不能使用过期的排队快照。 */
    private synchronized boolean prepareSend(Delivery claimed) {
        var row = get(claimed.id);
        if (!"WEBHOOK".equals(row.channel) || !validLease(row, claimed.lease)) {
            return false;
        }
        if (!enabled(row, settings())) {
            save(row, "CANCELLED", row.attempts, clock.instant(), "", "通知渠道已关闭或地址已改变");
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
                    d = claim("WEBHOOK");
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
            LoggerFactory.getLogger(getClass()).warn("通知投递暂不可用，稍后重试", e);
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
