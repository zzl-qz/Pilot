并行只读查看当前 Git 分支、暂存区、工作区修改与未跟踪文件列表。
在分析模式和子代理中均可直接与 read_file / search_text 并发调用，无需走 run_command。
