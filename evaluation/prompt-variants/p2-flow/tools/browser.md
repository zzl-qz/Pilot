在隔离的浏览器里验证本机 Web 应用：打开页面、读取 DOM、点击 / 输入 / 上传、截图，并查看控制台与网络请求。每个会话有独立的浏览器（cookie、存储互不可见）。
只能打开本机地址（localhost、127.0.0.1、[::1]）；外部网页内容用 web_fetch。

**标准流程**
1. 先用 run_command 后台启动应用，确认日志里已就绪，再 `navigate`。
2. `snapshot` 返回页面大纲，可交互元素带 `[ref=eN]`；`click` / `fill` / `type` / `select` / `upload` 用这个 ref。页面变化（跳转、弹层、列表刷新）后旧 ref 会失效，操作前重新 `snapshot`。
3. 用 `snapshot` 判断文字和状态，需要核对布局、样式、图片内容时用 `screenshot`——截图会作为图片交给你，你可以直接看。
4. 页面异常时看 `console`（报错、未捕获异常）和 `network`（`failedOnly=true` 只看失败请求），再决定改哪里。
5. 改完代码后 `reload` 或重新 `navigate`，用同样的步骤复验，并在结论里引用看到的页面文字、请求状态或控制台结果作为证据。

**动作**
- `navigate`（url）、`reload`、`back`：切换页面。
- `snapshot`：DOM 大纲；`screenshot`（可选 ref / selector / fullPage）。
- `click`、`fill`（清空后整体写入，可加 submit）、`type`（逐字输入，适合有按键监听的输入框）、`press`（key）、`select`（values）、`upload`（ref 指向 file input，files 为工作区内路径）。
- `wait`（text / selector / ms）：等待异步内容出现，优先等文本或元素，不要盲目长等。
- `console`、`network`：读取记录（可 clear）。
- `evaluate`（expression）：在页面内执行 JS 并返回结果，仅在 snapshot 看不到所需信息时使用，会触发审批。
- `close`：关闭本会话的浏览器页面。

**注意**
- 给文件选择框添加文件使用 upload，files 为工作区内已有文件；不要用 evaluate 伪造 input.files。
- 返回 ERROR 时读原因再换办法；「浏览器当前不可用」说明环境缺少浏览器，不要重试，如实告知用户。
- 连接被拒绝通常是服务没起来或端口不对，先查应用日志，不要反复 navigate。
- 浏览器里的 DELETE、提交表单等操作会真实改变应用数据，只对用户要求验证的流程执行。
