用一份补丁一次修改多个文件：新增、修改、删除、重命名。单文件的少量精确替换仍用 edit_file；改动跨多个文件或同一文件有多处连续变化时用本工具，省去逐文件往返。

格式（patch 参数的完整内容）：

```
*** Begin Patch
*** Update File: src/App.java
@@ class App
 保持不变的上下文行（行首一个空格）
-要删除的行
+要新增的行
*** Add File: src/Util.java
+新文件的每一行都以 + 开头
*** Delete File: old/Legacy.java
*** Update File: a/Old.java
*** Move to: a/New.java
@@
-旧
+新
*** End Patch
```

- 修改和删除前必须已经 read_file 过该文件（机制强制）；Add 的目标文件不能已存在。
- 每个 hunk 要带 2~3 行上下文，使其在文件里唯一命中；命中多处会报错并列出全部命中行号。可以用 `@@ 函数或类名`（支持子串匹配，如 `@@ class App` 可命中 `public final class App`）或标准 `@@ -起始行,行数 +起始行,行数 @@` 帮助定位。
- hunk 之间按文件中的出现顺序排列；一个补丁里同一文件只出现一次。
- 失败时按返回的最接近片段或匹配行号修正；同一文件连续失败 2 次即改用聚焦的 `edit_file`，不要反复重发大补丁。
