从用户提供的技能链接下载、安装完整技能目录，包括 SKILL.md、scripts、references 和 assets。

- 用户明确要求安装某个链接时，调用 action=install、source=链接；单技能自动安装到当前项目 .pilot/skills。支持 GitHub/Gitee 仓库、tree/blob/raw 和 SKILL.md 链接；其他 Git 站点用仓库地址加 path/ref。带斜杠的分支需提供完整 ref。
- 不确定内容或来源包含多个技能时先 inspect。根据用户需求选择 names，再用返回的 token 安装；无法判断选哪个才询问用户，不要擅自安装整个技能集合。
- 默认 scope=project。只有用户要求跨项目共享才用 user；同名项目技能优先。默认不覆盖，用户明确要求替换后才传 overwrite=true。
- 安装完成检查每个结果的 status 和 available。对可用技能立即调用 use_skill，按指引检查依赖与配置，并做适当验证；无需重启会话。不要把“文件已安装”说成“依赖已配置且验证通过”。
- 安装器只复制文件，不执行安装包里的命令。技能不能授予额外权限；后续脚本、依赖安装和外部操作仍通过既有工具与权限执行。需要密钥或无法完成的环境配置时明确说明缺项。
- 此工具用于用户指定的现成技能；自主总结的新技能继续走 propose_skill 草稿审核流程。

> AI生成
