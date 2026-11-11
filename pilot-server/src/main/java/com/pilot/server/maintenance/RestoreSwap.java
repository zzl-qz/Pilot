package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

/** 保存切换意图，进程在任一次重命名后退出也可以按记录还原。 */
final class RestoreSwap {
    record Step(String destination, String staging, String previous, boolean existed) {
        Step(Path destination, Path staging, Path previous) {
            this(destination.toString(), staging == null ? "" : staging.toString(), previous.toString(),
                exists(destination));
        }
    }

    record Transaction(List<Step> steps) {
    }

    @FunctionalInterface
    interface Check {
        void run() throws IOException;
    }

    private final ObjectMapper mapper;
    private final Path journal;

    RestoreSwap(Path root, ObjectMapper mapper) throws IOException {
        this.journal = DataDirectoryLock.control(root).resolve("restore.json");
        this.mapper = mapper;
    }

    void requireReady() throws IOException {
        if (exists(journal)) {
            throw new IOException("上次恢复未完成，请停服执行 maintenance rollback：" + journal);
        }
    }

    void apply(List<Step> steps, Check verify) throws IOException {
        requireReady();
        Transaction transaction = new Transaction(List.copyOf(steps));
        Files.createDirectories(journal.getParent());
        Path temporary = Files.createTempFile(journal.getParent(), "restore-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writeValueAsString(transaction), StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            move(temporary, journal);
        } finally {
            Files.deleteIfExists(temporary);
        }
        try {
            for (Step step : steps) {
                Path target = Path.of(step.destination());
                if (step.existed()) {
                    move(target, Path.of(step.previous()));
                }
                if (!step.staging().isEmpty()) {
                    move(Path.of(step.staging()), target);
                }
            }
            verify.run();
            Files.delete(journal);
        } catch (IOException | RuntimeException failure) {
            try {
                rollback(transaction);
            } catch (IOException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw new IOException("恢复失败，回滚需重试：" + journal, failure);
            }
            throw new IOException("恢复失败，原数据已还原：" + failure.getMessage(), failure);
        }
    }

    boolean rollback() throws IOException {
        if (!exists(journal)) {
            return false;
        }
        rollback(mapper.readValue(Files.readString(journal), Transaction.class));
        return true;
    }

    private void rollback(Transaction transaction) throws IOException {
        for (Step step : transaction.steps().reversed()) {
            Path target = Path.of(step.destination());
            Path previous = Path.of(step.previous());
            Path staging = step.staging().isEmpty() ? null : Path.of(step.staging());
            if (exists(previous)) {
                if (exists(target)) {
                    if (staging == null || exists(staging)) {
                        throw new IOException("恢复目标被其他文件占用：" + target);
                    }
                    move(target, staging);
                }
                move(previous, target);
            } else if (!step.existed() && staging != null && !exists(staging) && exists(target)) {
                move(target, staging);
            }
        }
        Files.delete(journal);
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    static void move(Path from, Path to) throws IOException {
        if (exists(to)) {
            throw new IOException("恢复目标已存在：" + to);
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }
}
