package com.pilot.core.tool;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ReadGuard} 的进程内实现：路径按规范化绝对路径去重，一次 Agent 运行一份实例。 */
public final class ReadTracker implements ReadGuard {

    private final Set<Path> observed = ConcurrentHashMap.newKeySet();

    @Override
    public void observed(Path file) {
        if (file != null) {
            observed.add(file.toAbsolutePath().normalize());
        }
    }

    @Override
    public boolean isObserved(Path file) {
        return file == null || observed.contains(file.toAbsolutePath().normalize());
    }
}
