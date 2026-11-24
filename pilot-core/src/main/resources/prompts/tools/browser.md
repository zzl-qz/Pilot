在浏览器里验证本机 Web 应用或受控外网流程：打开页面、多标签切换、读取 DOM、点击 / 输入 / 上传 / 下载、截图，并查看控制台与网络请求。每个会话使用自己的隔离页面与上下文；独立浏览器上下文隔离 cookie 与存储，桌面标签沿用所在工作区的浏览器分区。只能切换或关闭当前会话所属的标签。
默认只能打开本机回环地址（`localhost`、`127.0.0.1`、`[::1]`）；同主机跨端口回环重定向与未授权外网跳转均会被拦截。多步骤复杂验证可优先委派 `browser_agent` 浏览器子代理；纯静态网页文本读取可用 `web_fetch`。

**标准流程**
1. 先用 `run_command` 后台启动应用，确认日志里已就绪，再 `navigate`。
2. `snapshot` 返回页面大纲，可交互元素带 `[ref=eN]`；`click` / `fill` / `type` / `select` / `upload` 用这个 ref。页面变化（跳转、弹层、列表刷新）或用户接管恢复后旧 ref 会失效，操作前重新 `snapshot`。
3. 用 `snapshot` 判断文字和状态，需要核对布局、样式、图片内容时用 `screenshot`——截图会作为图片交给你并写入证据列表，你可以直接看。
4. 页面异常时看 `console`（报错、未捕获异常）和 `network`（`failedOnly=true` 只看失败请求），再决定改哪里。
5. 需要登录、MFA、CAPTCHA 人机验证或支付时，调用 `handoff`（`kind="auth"|"captcha"|"payment"|"manual"`、`reason`）等待用户亲自完成；恢复后先重新 `snapshot` 验证状态。
6. 改完代码后 `reload` 或重新 `navigate`，用同样的步骤复验，并在结论里引用看到的页面文字、请求状态、控制台结果或 `<browser_host_facts>` 作为证据。

**动作**
- `navigate`（url）、`new_tab`（url）、`tabs`、`switch_tab`（tabId）、`close_tab`（tabId）、`reload`、`back`、`forward`：打开与切换当前会话所属标签页。
- `snapshot`：DOM 大纲（自动穿透同源与嵌套 `iframe` 及开放 Shadow DOM，标记 `[frame=f1]` 与 `[ref=eN]`）；`screenshot`（可选 ref / selector / fullPage）。
- `inspect`（可选 ref / selector）：默认返回真实视口和前 30 个可见控件/布局区的位置、尺寸、ref；传 ref 或 selector 可继续读取指定元素的计算样式。布局诊断优先用此动作，不需要 `evaluate` 审批。
- `resize`（width / height）：设置验证视口，宽 280–1920、高 220–1600；桌面 1440×840、平板 768×840、手机 390×840。设置后重新 `snapshot` 和截图，用户接管时不可调整。
- `click`、`dblclick`、`right_click`、`hover`、`fill`（原生表单与富文本均支持清空后整体填写并回读校验，可加 submit）、`type`（支持 `clear=true` 先清空，中英文/符号混合逐字输入）、`press`（key）、`select`（values）、`check` / `uncheck`（精准切换复选框/开关）、`scroll`（支持 `direction`/`amount` 滚动视口/容器、传 `ref` 居中滚入或传 `(x,y)` 滚动）、`drag`（支持 DOM 目标 `targetRef`/`targetSelector` 或坐标 `targetX`/`targetY` 拖拽排序/滑块）、`dialog`（预设下一次原生 `confirm`/`prompt`/`alert` 确认或取消并传入 `promptText`）、`batch`（`steps` 数组一次批量串行执行最多 50 步表单/勾选动作）、`upload`（支持 `<input type=file>` 与点击动态按钮弹出 `filechooser` 上传，单文件 ≤25 MiB、总计 ≤50 MiB）、`download`（带危险扩展名 / MIME / 魔数与大小限制审查，违规文件立即取消并删除）。当元素位于 `<canvas>` 或无 DOM ref 时，可传 `(x, y)` 视觉坐标兜底操作。所有目标定位动作会自动居中滚动、穿透同源 `iframe` / 开放 Shadow DOM 并校验未遮挡命中点。
- `record`（mode="start"|"stop"|"status"）：录制可回放的浏览器动作帧与时间轴作为证据交付（敏感页自动跳过画面采集）。
- `handoff`（kind, reason）：遇到登录、验证码、MFA 或支付结账时释放控制锁定，等待用户在浏览器中完成后交回控制权。智能体自动操作期间页面会常驻蓝色「正在控制」锁框与「停止控制 (Stop)」按钮，仅当用户点击 Stop、触发 `handoff` 或 `close` 时解除。
- `wait`（text / selector / url / state="visible"|"hidden" / ms）：等待异步内容、路由或遮罩消失，优先等文本、URL 或元素，不要盲目长等。
- `console`、`network`：读取记录（可 `clear`；敏感查询串与凭证字段会自动脱敏）。
- `evaluate`（expression）：在页面内执行 JS 并返回结果（浏览器子代理禁用，主代理仅在 `snapshot` 与 `inspect` 看不到所需信息时使用，始终触发审批；敏感页禁止执行）。
- `close`：关闭本会话的浏览器页面。

**注意**
- 返回 ERROR 时读原因再换办法；「浏览器当前不可用」说明环境缺少浏览器，不要重试，如实告知用户。
- 连接被拒绝通常是服务没起来或端口不对，先查应用日志，不要反复 `navigate`。
- 浏览器里的 DELETE、提交表单等操作会真实改变应用数据，只对用户要求验证的流程执行。
- 支付 / 结账页上的自动交互与脚本执行（`click`、`fill`、`submit`、`evaluate` 等）会被宿主机直接拦截；请改用 `handoff(kind="payment")` 交由用户完成。

外网流程：使用 `external=true`、`purpose` 说明要看什么。完全放行时直接打开和操作，不再逐次确认。其他权限档位仍要确认。`evaluate` 任何档位都要确认。登录页和验证码要用户在页面上自己完成，模型过不了。用户接管后不要重试交互；用户恢复后重新 `snapshot` 验证状态，再按新 ref 继续，不要重放旧动作。
要看 Pilot 自己的界面，打开本机工作台地址，不要重启当前桌面进程。这个进程里跑着当前任务，拉起新的桌面端会把这一轮一起停掉。
外网 `click`/`fill`/`type`/`press`/`select`/`upload`/`download`/`evaluate` 还必须携带最近观察的 `expected_url`，页面改变后旧授权失效。
搜索筛选与申请准备使用 `snapshot`/`text`/`links` 读取；外网变更须用 `external=true`，不能用普通本机动作绕过确认。
提交申请/发送/发布前 `purpose` 必须列出最终对象、字段和影响，让用户对本次具体动作确认。不要把一次导航授权当作后续提交授权。
跨域重定向/新站点会拦截，需重新 `navigate` 并确认；登录、验证码和 MFA 交由用户接管。外网使用隔离浏览器，不导入桌面登录态。
