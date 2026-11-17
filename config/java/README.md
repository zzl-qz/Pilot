# Java 可读性规范

项目以 [阿里巴巴 Java 开发手册（官方 P3C 仓库）](https://github.com/alibaba/p3c) 为依据，使用适配 Java 25 的工具落实能够自动检查的规则，并对执行流程、异常边界和并发语义进行人工审阅。

## 格式与基础门禁

- Spotless 3.11.0 + Eclipse JDT 4.38：4 个空格缩进、120 列换行目标、统一导入顺序、删除无用导入、LF 换行和文件末尾换行。
- Checkstyle 14.4.0：明确导入、控制语句大括号、一行一条语句、每个声明只定义一个变量、类型/成员/参数/局部变量命名、常量命名、`L` 后缀、数组声明、修饰符顺序、禁止空语句，以及 `equals` / `hashCode` 配对。
- `log` / `logger` 保留日志字段惯用名称；其他常量使用大写下划线格式。
- 格式化覆盖 `pilot-core`、`pilot-server` 的生产代码、单元测试和 Java 测试夹具；Checkstyle 检查生产代码和单元测试。生成目录、第三方依赖不参与改写。
- 注释和已有字符串内容保持原样。超过 120 列且不能安全拆分的字符串、链接、注释仍可能存在；120 列是格式器的换行目标，不是已经清零的字符数门禁。

在仓库根目录执行，要求 JDK 25：

```sh
# 自动整理格式、导入；不能代替方法拆分或行为测试
./mvnw spotless:apply

# 只检查，不修改文件
./mvnw spotless:check checkstyle:check

# 完整构建、测试和规范门禁
./mvnw verify

# 含前端、桌面逻辑的可靠性检查；可追加 --containers --ui 做实测
node scripts/verify-reliability.mjs
```

Windows PowerShell 使用 `.\mvnw.cmd` 替换 `./mvnw`。配置使用 Maven 仓库根目录和相对源码路径，不依赖开发者本机路径。`.editorconfig` 与 `.gitattributes` 统一编辑、检出换行；容器内构建命令仍按 Linux 容器能力选择，不能把它当作宿主 Windows 实测。

两个检查均绑定 `verify`，三平台 CI 和可靠性脚本会调用。只运行 `test` 不执行 `verify` 阶段，提交前应额外执行上述只检查命令或完整验证。

## 存量坏味道门禁

`checkstyle.xml` 只管形式规范，判不了「方法太长、参数太多、代码在重复」。这部分由 [checkstyle-strict.xml](checkstyle-strict.xml) 和 [quality-gate.mjs](../../scripts/quality-gate.mjs) 负责。

**`checkstyle-strict.xml` 是生成文件，不要手改。** 规则的 id、阈值、文案都在 [config/quality/rules.json](../quality/rules.json)，改完执行 `node scripts/quality-sync.mjs --write` 重新生成；CI 会拒绝两边不一致的提交。规则的增删流程见 [质量规则目录](../quality/README.md)。

```sh
# 只检查本次改动新引入的坏味道（默认，基线是 HEAD）
node scripts/quality-gate.mjs

# 指定对比基线，例如 PR 里对比目标分支
node scripts/quality-gate.mjs --base origin/master

# 全量盘点存量（诊断用，存量问题也会报出来）
node scripts/quality-gate.mjs --all
```

判定方式是**做差集，不是维护快照**：把改动文件在基线版本上的检查结果也跑一遍，只报「改动行上、且基线版本没有」的违例。这样存量债务不会卡住提交，行号漂移和文件改名也不需要维护基线文件。

规则分五组：结构臃肿、边界与资源、冗余写法、依赖与架构、工具链自检。其中后两组和前端语言规则由门禁自己实现，不依赖第三方检查器：

- 依赖方向：`com.pilot.core` 不允许 import `com.pilot.server`。
- 压制后门：新增 `@SuppressWarnings("all")`、`NOSONAR`、`// noinspection`、整段 `eslint-disable` 会被拦下；只抑制具体规则的写法不受影响。
- 静默失效防护：检查器没返回结果的文件、带 UTF-8 BOM 的文件都会直接报错。Checkstyle 遇到 BOM 会把文件解析成空结果且不报错，表现是「门禁通过但什么都没查」，这一条专门堵它。

阈值口径（包括圈复杂度为什么取 20 而不是 10）记录在 `rules.json` 对应规则的 `note` 字段里，和实现放在一起，避免文档和配置各说一套。

门禁直接调用本地 Maven 仓库里的 Checkstyle，不启动 Maven，所以**首次使用前需要跑过一次 `./mvnw verify`** 把依赖填进缓存；缓存位置不是默认的 `~/.m2/repository` 时用 `MAVEN_REPO` 指定。

门禁直接调用本地 Maven 仓库里的 Checkstyle，不启动 Maven，所以**首次使用前需要跑过一次 `./mvnw verify`** 把依赖填进缓存；缓存位置不是默认的 `~/.m2/repository` 时用 `MAVEN_REPO` 指定。

## 审阅要求

1. 方法按职责组织；主流程展示顺序，细节交给有业务含义的私有方法。不要为了满足行数机械拆成许多没有意义的方法。
2. 使用早返回处理前置条件，减少嵌套三元表达式和多层条件分支。时间、次数和输出截断限额使用带单位/含义的常量。
3. 保留公共接口、JSON 字段、数据库字段和状态字符串；内部命名调整不能变更外部协议。
4. 异常必须保留原始失败原因；清理或回执再次失败时保留诊断信息。可预期的能力探测、关停竞态可以降级，但要说明原因和备用路径。
5. 调整并发代码时同时检查生命周期、容量限制、锁覆盖范围、取消和拒绝处理。虚拟线程使用应结合任务预算、许可和生命周期审阅，不能机械套用平台线程池的参数。
6. 拆分恢复、权限和落盘流程时，保持原有检查顺序、CAS、锁和 `finally` 释放范围，并验证失败路径。

## 工具边界与来源

官方 [P3C PMD 配置](https://github.com/alibaba/p3c/blob/master/p3c-pmd/pom.xml) 使用 PMD 6.15.0；它不能作为本项目 Java 25 语法的完整验证依据。本项目使用 [Checkstyle](https://checkstyle.org/) 的当前语法解析器和 [Spotless](https://github.com/diffplug/spotless/tree/main/plugin-maven) 集成格式器。检查通过只表示已配置的规则通过，不表示手册中的架构、性能、并发、数据库等要求全部满足。

`alibaba-eclipse.xml` 来自 [Alibaba P3C Eclipse 格式配置](https://github.com/alibaba/p3c/blob/master/p3c-formatter/eclipse-codestyle.xml)，按 Apache-2.0 使用；原始许可证见 [LICENSE-p3c.txt](LICENSE-p3c.txt)。本项目于 2026-11-04 调整了 Java 25 源级别、单行语句/方法体展开和注释保留选项。未将其宣称为 Alibaba 官方维护的项目配置。

本次扫描结果、已拆分的方法、测试记录和后续项见 [Java 可读性审阅记录](../../evaluation/JAVA_READABILITY_2026-11-04.md)。
