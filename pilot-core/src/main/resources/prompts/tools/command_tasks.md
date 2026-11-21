列出本机所有会话的命令任务，运行中的排在前面，返回任务 ID、所属会话、状态、PID、工作目录和命令，用于换会话后接管之前留下的后台任务。

- 只读，不会停止任何进程；`includeFinished=false` 只看运行中的。
- 任务按会话归属。看到不属于当前会话的任务，查状态、读日志、停止时把该行的会话作为 `ownerSessionId` 传给 command_status / command_logs / stop_command。
- 服务重启后仍在运行的任务标为 `INTERRUPTED`，表示旧进程所有权已失效，不要按旧 PID 去操作它。
- 要判断端口被谁占着用 process_list；这里只解决「任务是谁的、还活着没有」。