在工作区内执行命令：查看仓库、构建、测试、启动开发服务、探测本机服务。默认工作区根目录；子项目用 `workdir` 指定，例如 `workdir="pilot-web"` 配合 `command="npm test"`，也可以写成 `cd pilot-web && npm test`（开头的 cd 会折算成 workdir，且必须在工作区内）。

**能写什么**
- 查看类（只读，任何权限档位都直接执行）：{{inspection}}；git 只读子命令 {{gitReadonly}}；`curl` 只读请求本机服务。
- 构建与运行（按权限档位审批）：{{development}}。
- 写文件的基础命令（按权限档位审批，路径限工作区内）：{{mutators}}；git 另允许 {{gitWrite}}，push/reset/clean/checkout 需要用户手动执行。改源码请用 edit_file / apply_patch，不要用这些命令改代码。
- 可以用 `&&`、`||`、`;`、`|` 串联最多 8 条，例如 `git diff --stat | head -20`、`mvn -q test 2>&1 | tail -40`；每一段都单独校验。
- 不是 shell：没有重定向（只放行 `2>&1`）、命令替换、`$变量`、`~`、通配符展开（`*` 原样传给程序），后台运行用 `background=true`。需要通配时让 rg / find / glob_files 自己处理。
- 文件类命令（cat、ls、cp 等）的路径必须在工作区内；`find` 不能带 -exec / -delete；`curl` 只能访问 localhost / 127.0.0.1 / [::1]，外部网页用 web_fetch。
- 禁止发布包（publish）和全局安装（-g）。

**怎么用**
- 构建和测试类命令把 timeoutSeconds 设到 300（默认 120，最大 600），不要因为超时就跳过验证。
- 若失败原因是目录、依赖或命令不存在，先修正运行环境再重试；这类错误不代表代码测试失败。
- {{backgroundHelp}}
- 开发服务器应直接运行前台命令并用 background=true 托管，不要再传守护化、脱离进程等参数，以便持续读取日志和停止进程。启动后先用 command_logs 看日志，再用 `curl -sS -i http://localhost:端口/` 确认已就绪（看状态码），若提供 browser 工具，页面行为用它验证。
