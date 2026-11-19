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

/** {@link ReadGuard} 的进程内实现：一次 Agent 运行一份。读过的文件记下修改时间、大小和内容指纹，外部改过就拒绝盲写。 */
public final class ReadTracker implements ReadGuard {

    private record FileStamp(long modifiedMillis, long sizeBytes, String digest) {
    }

    private final ConcurrentMap<Path, FileStamp> observed = new ConcurrentHashMap<>();

    @Override
    public void observed(Path file) {
        if (file == null) {
            return;
        }
        Path key = file.toAbsolutePath().normalize();
        observed.put(key, stamp(key));
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
