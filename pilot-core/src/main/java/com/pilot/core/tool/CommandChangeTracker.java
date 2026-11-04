package com.pilot.core.tool;

import java.nio.file.Path;
import java.util.List;

/**
 * Tracks workspace files around a command without pretending build products are source edits.
 * Implementations may persist the opaque handle so a background command can finish in a later run.
 */
public interface CommandChangeTracker {
    Handle begin(Path workspace);

    Result finish(Handle handle);

    record Handle(String runId, String id) {
        public Handle {
            runId = runId == null ? "" : runId;
            id = id == null ? "" : id;
        }

        public boolean active() {
            return !runId.isBlank() && !id.isBlank();
        }

        public static Handle none() {
            return new Handle("", "");
        }
    }

    record Result(List<String> changedPaths, boolean complete, String warning) {
        public Result {
            changedPaths = changedPaths == null ? List.of() : changedPaths.stream()
                .filter(path -> path != null && !path.isBlank()).map(String::strip).distinct().sorted().toList();
            warning = warning == null ? "" : warning.strip();
        }

        public static Result empty() {
            return new Result(List.of(), true, "");
        }
    }

    static CommandChangeTracker none() {
        return new CommandChangeTracker() {
            @Override
            public Handle begin(Path workspace) {
                return Handle.none();
            }

            @Override
            public Result finish(Handle handle) {
                return Result.empty();
            }
        };
    }
}
