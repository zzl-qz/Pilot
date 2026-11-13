按 glob 在子树里一次找出所有匹配的文件，按修改时间从新到旧返回。需要按文件名或扩展名定位（例如 `**/*Controller.java`、`src/**/*.test.ts`）时用它，不要用 list_dir 逐层翻目录。
pattern 相对 path（默认工作区根目录）；自动跳过 .git、node_modules、target、build 等依赖和产物目录。按内容找代码用 search_text。
