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
- 每个 hunk 要带 2~3 行上下文，使其在文件里唯一命中；命中多处或找不到会整份补丁失败，不会写入任何文件。可以用 `@@ 函数或类名` 帮助定位。
- hunk 之间按文件中的出现顺序排列；一个补丁里同一文件只出现一次。
- 失败时按返回的文件片段重写对应 hunk，不要原样重试。

> AI生成
