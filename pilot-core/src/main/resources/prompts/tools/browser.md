在浏览器里验证本机 Web 应用：打开页面、读取 DOM、点击 / 输入 / 上传、截图，并查看控制台与网络请求。每个会话使用自己的页面；独立浏览器上下文隔离 cookie 与存储，桌面标签沿用所在工作区的浏览器分区。只能切换或关闭当前会话所属的标签。
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
- `inspect`（可选 ref / selector）：默认返回真实视口和前 30 个可见控件/布局区的位置、尺寸、ref；传 ref 或 selector 可继续读取指定元素的计算样式。布局诊断优先用此动作，不需要 evaluate 审批。
- `resize`（width / height）：设置验证视口，宽 280–1920、高 220–1600；桌面 1440×840、平板 768×840、手机 390×840。设置后重新 snapshot 和截图，用户接管时不可调整。
- `click`、`fill`（清空后整体写入，可加 submit）、`type`（逐字输入，适合有按键监听的输入框）、`press`（key）、`select`（values）、`upload`（ref 指向 file input，files 为工作区内路径）。
- `wait`（text / selector / ms）：等待异步内容出现，优先等文本或元素，不要盲目长等。
- `console`、`network`：读取记录（可 clear）。
- `evaluate`（expression）：在页面内执行 JS 并返回结果，仅在 snapshot 看不到所需信息时使用，会触发审批。
- `close`：关闭本会话的浏览器页面。

**注意**
- 返回 ERROR 时读原因再换办法；「浏览器当前不可用」说明环境缺少浏览器，不要重试，如实告知用户。
- 连接被拒绝通常是服务没起来或端口不对，先查应用日志，不要反复 navigate。
- 浏览器里的 DELETE、提交表单等操作会真实改变应用数据，只对用户要求验证的流程执行。


外网流程：使用 external=true、purpose 明确目标与影响，navigate 只支持公开 HTTPS 域名；每次均请求用户确认。
外网 click/fill/type/press/select/upload/download/evaluate 还必须携带最近观察的 expected_url，页面改变后旧授权失效。
搜索筛选与申请准备使用 snapshot/text/links 读取；外网变更须用 external=true，不能用普通本机动作绕过确认。
提交申请/发送/发布前 purpose 必须列出最终对象、字段和影响，让用户对本次具体动作确认。不要把一次导航授权当作后续提交授权。
跨域重定向/新站点会拦截，需重新 navigate 并确认；登录、验证码和 MFA 交由用户接管。外网使用隔离浏览器，不导入桌面登录态。
