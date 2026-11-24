并行只读查看工作区或暂存区（staged=true）的 Git diff，支持按 path 过滤、stat_only 摘要以及行数上限截断。
大改动时建议先 stat_only=true 看受影响文件，再按具体 path 查看详细补丁。
