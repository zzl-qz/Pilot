向以 `interactive=true` 启动的命令写标准输入，用于 npm init、REPL、等确认提示这类会一直等待输入的程序。

- 先 `run_command(..., background=true, interactive=true)` 同时拿到任务 ID；`interactive` 只能配 background，且只允许单条命令（串联、管道、重定向都不行）。
- `text` 原样写入，**不自动补换行**：需要回车就自己写 `\n`。写完用 `command_logs` 看程序的响应，再决定下一步。
- 程序读完输入就退出时，用 `endInput=true` 发送 EOF，否则它会一直等下去直到超时。
- 每条最多 8192 字符；命令已结束或启动时没带 `interactive` 会报错。
- 这是管道不是终端：依赖 isatty 的交互界面（彩色提示、行编辑、进度条）仍会走非交互分支，提示词可能不出现。这类程序优先改用非交互参数（如 `npm init -y`、`ssh -o BatchMode=yes`）。
- 写输入属于执行动作，不保证命令结果正确；仍然要用 `command_status` 等到终态再判断成败。