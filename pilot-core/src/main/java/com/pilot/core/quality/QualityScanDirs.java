package com.pilot.core.quality;

import java.util.Set;

/** Directories excluded from workspace quality scans. */
final class QualityScanDirs {
    static final Set<String> SKIPPED =
        Set.of(".git", ".pilot", ".pilot-eval", ".pilot-task-envs", ".pilot-maintenance", ".idea", ".vscode", ".gradle",
            ".venv", "venv", "__pycache__", "node_modules", "target", "build", "dist", "out", "coverage", "test",
            "tests", "__tests__", "spec", "specs", "fixture", "fixtures", "evaluation", "docs", "docs-public");

    private QualityScanDirs() {
    }
}
