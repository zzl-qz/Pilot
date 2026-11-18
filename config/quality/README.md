# 质量规则目录

`rules.json` 是全仓代码质量规则的**唯一事实源**：规则 id、阈值、适用语言、提示文案、由谁执行，都只写在这里。各引擎的配置由它生成，不再手写。

## 为什么要这一层

提交门禁的阈值只放在这一份目录里，生成 Checkstyle 配置。CI 会拒绝目录和生成文件各写各的。代理到别的仓库干活时不读这份目录。

## 文件结构

```
config/quality/rules.json            唯一事实源
config/quality/README.md             本文件
config/java/checkstyle-strict.xml    生成：提交门禁用的 Checkstyle 规则
```

生成文件入库；`--check` 会拒绝任何「目录改了、文件没重生成」的提交。

一条规则长这样：

```json
{
  "id": "long-method",
  "title": "方法过长",
  "kind": "structure",
  "languages": ["java"],
  "unit": "行",
  "limit": 80,
  "hint": "按职责拆成有业务含义的私有方法，不要为凑行数机械拆分",
  "engines": [{ "engine": "checkstyle", "module": "MethodLength", "property": "max" }]
}
```

字段含义：`kind` 决定分组（structure / boundary / redundancy / architecture / hygiene）；`languages` 决定它约束哪种语言；`engines` 声明由谁执行，空数组表示还没人执行——这种规则必须标 `"status": "planned"` 并写 `blockedBy`，否则 CI 会拒绝。

## 常用命令

```sh
# 查看规则清单（按分组，带语言、状态、执行引擎）
node scripts/quality-sync.mjs --list

# 改完 rules.json 后重新生成各引擎配置
node scripts/quality-sync.mjs --write

# 只检查是否漂移（CI 用；不一致退出码 1）
node scripts/quality-sync.mjs
```

## 加一条规则

1. 在 `rules.json` 里加一条，`id` 用 kebab-case，写清 `kind`、`languages`、`hint`。
2. 选引擎：
   - 度量类（行数、复杂度、参数个数）→ `checkstyle`，同时给出 `limit` 和 `property`
   - 形状类（禁某个写法、依赖方向、编码问题）→ `quality-gate`，`impl` 必须是门禁里已有的实现
   - 跨语言形状类 → `semgrep`，但这套引擎还没接入，只能标 `planned`
   - 代理在别的仓库里做规范检查时，不读这份目录，而是跑那个仓库自己的 lint
3. `node scripts/quality-sync.mjs --write` 重新生成。
4. `node --test scripts/quality-rules.test.mjs` 确认三方咬合；动过 Java 阈值再跑 `./mvnw -pl pilot-core -Dtest=com.pilot.core.quality.*Test test`。

加一种语言同理：把 `languages` 里的值换掉，再给对应语言挑引擎。**规则是数据，引擎是插件**——扩语言不需要改门禁代码，只需要接一个新引擎。

## 一致性由测试保证

[quality-rules.test.mjs](../../scripts/quality-rules.test.mjs) 断言这些事，任何一条不成立 CI 就红：

- 目录结构合法（id 唯一、kebab-case、active 必须有引擎、planned 必须写阻塞原因）
- 提交的 `checkstyle-strict.xml` 与目录生成结果逐字节一致
- 每条 Java 规则的模块名在生成的配置里出现恰好一次，阈值与 `limit` 相同
- 门禁声明实现的规则和目录要求的一致，不多不少
- 生成的配置里每个阈值都能追溯到一条 `limit`
- `planned` 规则不得声明引擎

这套断言的目的是让"文档说 80、配置写 120、实际不查"这种漂移无法存在超过一次提交。代理收尾时的规范检查不读这里的阈值，而是执行当前工作区自己的 lint。