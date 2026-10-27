在工作区根目录执行一条受限命令并返回退出码与输出，主要用于构建和测试验证。

- 只允许 {{executables}} 等白名单程序，不经过 shell：不能用管道、重定向、&& 串联。
- git 允许 {{gitReadonly}} 等只读子命令，以及 {{gitWrite}}；push/reset/clean/checkout 等需要用户手动执行。
- 构建和测试类命令把 timeoutSeconds 设到 300（默认 120，最大 600），不要因为超时就跳过验证。
