package com.pilot.core.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link ReadGuard} 的进程内实现：一次 Agent 运行一份。读过的文件记下修改时间、大小和内容指纹，外部改过就拒绝盲写。 */
public final class ReadTracker implements ReadGuard {

    private record FileStamp(long modifiedMillis, long sizeBytes, String digest) {
    }

    /** 同一文件完整交出正文的次数。再多就是在换行号重读。 */
    public static final int MAX_DELIVERIES = 3;
    /** 一次运行里没有任何写入时，只读查看的上限。再查就是在空转。 */
    public static final int MAX_INSPECTIONS = 16;

    private final ConcurrentMap<Path, FileStamp> observed = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    private final AtomicInteger revision = new AtomicInteger();
    private final AtomicInteger inspections = new AtomicInteger();

    @Override
    public void observed(Path file) {
        if (file == null) {
            return;
        }
        Path key = file.toAbsolutePath().normalize();
        FileStamp next = stamp(key);
        FileStamp previous = observed.put(key, next);
        if (previous != null && !previous.equals(next)) {
            deliveries.remove(key);
            inspections.set(0);
        }
        if (previous == null || !previous.equals(next)) {
            revision.incrementAndGet();
        }
    }

    @Override
    public boolean deliverRead(Path file) {
        if (file == null) {
            return true;
        }
        Path key = file.toAbsolutePath().normalize();
        return deliveries.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet() <= MAX_DELIVERIES;
    }

    @Override
    public boolean deliverInspection() {
        return inspections.incrementAndGet() <= MAX_INSPECTIONS;
    }

    @Override
    public void noteWrite() {
        inspections.set(0);
    }

    @Override
    public String observationStamp(Path file) {
        if (file == null) {
            return "";
        }
        FileStamp now = stamp(file.toAbsolutePath().normalize());
        if (now.modifiedMillis() < 0) {
            return "";
        }
        return now.modifiedMillis() + ":" + now.sizeBytes() + ":" + now.digest();
    }

    @Override
    public int revision() {
        return revision.get();
    }

    @Override
    public boolean isObserved(Path file) {
        return file == null || observed.containsKey(file.toAbsolutePath().normalize());
    }

    @Override
    public boolean isFresh(Path file) {
        if (file == null) {
            return true;
        }
        Path key = file.toAbsolutePath().normalize();
        FileStamp seen = observed.get(key);
        return seen == null || seen.equals(stamp(key));
    }

    private static FileStamp stamp(Path file) {
        try {
            BasicFileAttributes attributes =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new FileStamp(attributes.lastModifiedTime().toMillis(), attributes.size(), digest(file));
        } catch (IOException e) {
            return new FileStamp(-1, -1, "");
        }
    }

    /** 同样长度的替换不会改变大小，必须比较内容。 */
    private static String digest(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }
}
