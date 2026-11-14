package com.pilot.server.mobile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pilot.core.util.Sha256;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.maintenance.DataMaintenance;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * 持久回执与设备绑定的限时请求标识。所有访问（包括过滤器执行）共用此对象锁。
 * 删除前持久化时间下限，时钟回拨或重启不能使已清理的请求重新生效。
 */
@Component
public class MobileReceiptStore {
    public static final String TOKEN_PATTERN = "r3\\.[0-9]{1,16}\\.[a-f0-9-]{36}\\.[a-f0-9]{64}";
    private static final long RESPONSE_RESERVE = 24L * 1024 * 1024;
    private final Path directory;
    private final DataMaintenance maintenance;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long lifetime;
    private final long retention;
    private final int maxCount;
    private final long maxBytes;
    private State state;
    private long count;
    private long bytes;
    private long lastCleanup;
    private int lastRemoved;
    private volatile String maintenanceError = "";

    @Autowired
    public MobileReceiptStore(PilotDataDir data, ObjectMapper mapper,
        @Value("${pilot.mobile.receipts.request-hours:24}") int requestHours,
        @Value("${pilot.mobile.receipts.retention-days:30}") int retentionDays,
        @Value("${pilot.mobile.receipts.max-count:10000}") int maxCount,
        @Value("${pilot.mobile.receipts.max-bytes:536870912}") long maxBytes) {
        this(data, mapper, Clock.systemUTC(), Duration.ofHours(requestHours), Duration.ofDays(retentionDays), maxCount,
            maxBytes);
    }

    MobileReceiptStore(PilotDataDir data, ObjectMapper mapper, Clock clock, Duration lifetime, Duration retention,
        int maxCount, long maxBytes) {
        if (lifetime.isNegative() || lifetime.isZero() || retention.isNegative() || maxCount < 1
            || maxBytes < RESPONSE_RESERVE) {
            throw new IllegalArgumentException("回执有效期、保留期或容量配置无效");
        }
        this.directory = data.file("mobile", "receipts");
        this.maintenance = data.maintenance();
        this.mapper = mapper;
        this.clock = clock;
        this.lifetime = lifetime.toMillis();
        this.retention = retention.toMillis();
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
    }

    private void initialize() throws IOException {
        if (state != null) {
            return;
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("state.json");
        State loaded;
        if (Files.exists(file)) {
            loaded = mapper.readValue(Files.readString(file), State.class);
            if (loaded.key() == null || !loaded.key().matches("[a-f0-9]{64}") || loaded.floor() < 0) {
                throw new IOException("回执安全状态损坏，停止接收新写操作");
            }
        } else {
            // 旧版回执可保守迁移；新版回执存在时不得偷偷重新生成签名密钥。
            for (Path receipt : files()) {
                if (read(receipt).expiresAt() > 0) {
                    throw new IOException("回执安全状态缺失，请恢复完整数据目录");
                }
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            loaded = new State(HexFormat.of().formatHex(key), Math.max(0, clock.millis()));
            write(file, loaded);
        }
        long total = 0;
        List<Path> receipts = files();
        for (Path receipt : receipts) {
            total += Files.size(receipt);
        }
        count = receipts.size();
        bytes = total;
        state = loaded;
    }

    public synchronized String issue(String peer) throws IOException {
        validatePeer(peer);
        initialize();
        checkCapacity();
        checkpoint();
        String unsigned = "r3." + Math.addExact(now(), lifetime) + "." + UUID.randomUUID();
        return unsigned + "." + sign(peer, unsigned);
    }

    static void validatePeer(String peer) {
        if (peer == null || !peer.matches("[a-f0-9]{64}")) {
            throw new Rejected(Rejection.INVALID_DEVICE);
        }
    }

    long validateNew(String id) throws IOException {
        verifySignature(id);
        checkpoint();
        long expiry = Long.parseLong(id.split(":", 2)[1].split("\\.")[1]);
        if (expiry <= now()) {
            throw new Rejected(Rejection.REQUEST_EXPIRED);
        }
        checkCapacity();
        return expiry;
    }

    private void verifySignature(String id) throws IOException {
        initialize();
        String[] parts = id == null ? new String[0] : id.split(":", 2);
        if (parts.length != 2 || !parts[0].matches("[a-f0-9]{64}") || !parts[1].matches(TOKEN_PATTERN)) {
            throw new Rejected(Rejection.RECEIPT_UPGRADE_REQUIRED);
        }
        String token = parts[1];
        int separator = token.lastIndexOf('.');
        String unsigned = token.substring(0, separator);
        if (!MessageDigest.isEqual(sign(parts[0], unsigned).getBytes(StandardCharsets.US_ASCII),
            token.substring(separator + 1).getBytes(StandardCharsets.US_ASCII))) {
            throw new Rejected(Rejection.INVALID_REQUEST_TOKEN);
        }
    }

    /** 查询只验证签名，不执行原操作；过期、容量耗尽时仍可核对结果。 */
    public synchronized Receipt lookup(String id) throws IOException {
        verifySignature(id);
        return get(id);
    }

    public synchronized void acknowledgeRequest(String id) throws IOException {
        verifySignature(id);
        String[] parts = id.split(":", 2);
        acknowledge(parts[0], parts[1]);
    }

    private String sign(String peer, String unsigned) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(state.key()), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((peer + ":" + unsigned).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkpoint() throws IOException {
        long time = now();
        if (time > state.floor()) {
            State next = new State(state.key(), time);
            write(directory.resolve("state.json"), next);
            state = next;
        }
    }

    private void checkCapacity() {
        if (count >= maxCount || bytes > maxBytes - RESPONSE_RESERVE) {
            throw new Rejected(Rejection.RECEIPT_CAPACITY);
        }
    }

    long now() {
        return Math.max(clock.millis(), state.floor());
    }

    Receipt get(String id) throws IOException {
        initialize();
        Path file = path(id);
        return Files.exists(file) ? read(file) : null;
    }

    void save(String id, Receipt receipt) throws IOException {
        initialize();
        Path file = path(id);
        boolean existed = Files.exists(file);
        long previous = existed ? Files.size(file) : 0;
        write(file, receipt);
        bytes += Files.size(file) - previous;
        if (!existed) {
            count++;
        }
    }

    public synchronized void acknowledge(String peer, String token) throws IOException {
        validatePeer(peer);
        if (token == null || !(token.matches(TOKEN_PATTERN) || token.matches("[a-f0-9-]{36}"))) {
            throw new Rejected(Rejection.INVALID_REQUEST_ID);
        }
        String id = peer + ":" + token;
        Receipt receipt = get(id);
        if (receipt == null) {
            // 确认丢包后允许重复确认，但绝不创建回执或执行原操作。
            return;
        }
        if (receipt.status() == 0 || receipt.status() >= 500) {
            throw new Rejected(Rejection.RESULT_UNCERTAIN);
        }
        if (receipt.acknowledgedAt() == 0) {
            save(id, new Receipt(receipt.fingerprint(), receipt.status(), receipt.contentType(), receipt.body(),
                receipt.createdAt(), receipt.expiresAt(), now()));
        }
    }

    public synchronized Status status() throws IOException {
        initialize();
        long unknown = 0;
        long unconfirmed = 0;
        long legacy = 0;
        long corrupt = 0;
        for (Path file : files()) {
            try {
                Receipt receipt = read(file);
                if (receipt.status() == 0 || receipt.status() >= 500) {
                    unknown++;
                } else if (receipt.acknowledgedAt() == 0) {
                    unconfirmed++;
                }
                if (receipt.expiresAt() == 0) {
                    legacy++;
                }
            } catch (RuntimeException | IOException e) {
                corrupt++;
            }
        }
        return new Status(count, bytes, unknown, unconfirmed, legacy, corrupt, maxCount, maxBytes,
            count >= maxCount || bytes > maxBytes - RESPONSE_RESERVE, lifetime, retention, lastCleanup, lastRemoved,
            maintenanceError);
    }

    public synchronized Status cleanup() throws IOException {
        initialize();
        long time = now();
        // 必须先成功持久化下限，再删除；即使随后崩溃或系统时间回拨也无法复活旧标识。
        State next = new State(state.key(), time);
        write(directory.resolve("state.json"), next);
        state = next;
        int removed = 0;
        for (Path file : files()) {
            Receipt receipt;
            try {
                receipt = read(file);
            } catch (RuntimeException | IOException e) {
                continue; // 损坏回执保留，不能因为读取失败把它当成未执行。
            }
            if (receipt.expiresAt() > 0 && receipt.expiresAt() <= time && receipt.status() > 0 && receipt.status() < 500
                && receipt.acknowledgedAt() > 0 && receipt.acknowledgedAt() <= time - retention) {
                long size = Files.size(file);
                Files.delete(file);
                count--;
                bytes -= size;
                removed++;
            }
        }
        lastCleanup = time;
        lastRemoved = removed;
        maintenanceError = "";
        return status();
    }

    @Scheduled(fixedDelayString = "${pilot.mobile.receipts.cleanup-ms:3600000}", initialDelay = 60000)
    public void scheduledCleanup() {
        maintenance.whenAvailable(() -> {
            try {
                cleanup();
            } catch (RuntimeException | IOException e) {
                maintenanceError = "回执清理失败，记录已保留，请检查数据目录与磁盘状态";
                LoggerFactory.getLogger(MobileReceiptStore.class).warn(maintenanceError);
            }
        });
    }

    private List<Path> files() throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList();
        }
    }

    private Receipt read(Path file) throws IOException {
        Receipt receipt = mapper.readerFor(Receipt.class).without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .readValue(Files.readString(file));
        if (receipt.fingerprint() == null || receipt.contentType() == null || receipt.body() == null
            || receipt.status() < 0 || receipt.status() > 599) {
            throw new IOException("回执损坏，不能重做原操作");
        }
        return receipt;
    }

    private Path path(String id) {
        return directory.resolve(digest(id.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    static String digest(byte[] bytes) {
        return Sha256.hex(bytes);
    }

    private void write(Path file, Object value) throws IOException {
        Path temp = Files.createTempFile(directory, "receipt-", ".tmp");
        try {
            byte[] encoded = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                if (Files.exists(file)) {
                    throw new IOException("数据目录不支持原子替换，已保留旧回执；请改用支持原子移动的本地磁盘", e);
                }
                Files.move(temp, file);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    record State(String key, long floor) {
    }

    public record Receipt(String fingerprint, int status, String contentType, String body, long createdAt,
        long expiresAt, long acknowledgedAt) {
    }

    public record Status(long count, long bytes, long unknown, long unconfirmed, long legacy, long corrupt,
        int maxCount, long maxBytes, boolean capacityReached, long requestLifetimeMillis, long retentionMillis,
        long lastCleanupAt, int lastRemoved, String maintenanceError) {
    }

    /** 回执请求被拒绝的原因：HTTP 状态和给用户的提示放在一处，枚举名就是返回的错误码。 */
    public enum Rejection {
        INVALID_DEVICE(400, "设备标识无效，请更新电脑连接组件"), REQUEST_EXPIRED(410, "请求已过期，请查看任务记录核对结果；不会重新执行"),
        RECEIPT_UPGRADE_REQUIRED(426, "请更新手机和电脑连接组件；旧请求只能核对已有回执"), INVALID_REQUEST_TOKEN(400, "请求标识签名无效，请核对原请求"),
        RECEIPT_CAPACITY(507, "移动回执空间不足，请在电脑端核对待确认记录并清理；读取和结果核对仍可使用"), INVALID_REQUEST_ID(400, "无效的回执标识"),
        RESULT_UNCERTAIN(409, "结果仍待核实，不能确认或清理");

        private final int status;
        private final String message;

        Rejection(int status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    public static class Rejected extends RuntimeException {
        private final int status;
        private final String code;
        private final boolean uncertain;

        /** 被拒绝的请求一律按「结果不确定」返回，客户端只核对、不重放。 */
        public Rejected(Rejection reason) {
            super(reason.message);
            this.status = reason.status;
            this.code = reason.name();
            this.uncertain = true;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public boolean uncertain() {
            return uncertain;
        }
    }
}
