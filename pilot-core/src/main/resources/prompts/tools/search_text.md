在工作区文件中按正则搜索，返回 路径:行号: 内容。可用 glob 限定文件类型，例如 *.java。
会跳过 target、node_modules 这类构建与依赖目录。
- 宽范围搜索先传 files_only=true，只看命中文件与匹配次数分布，避免数百行噪点占满上下文。
- 定位具体符号或调用点时可传 context_lines（1–5），直接带回前后上下文行，省去再调一轮 read_file。
- 不知道代码在哪个文件时先用它定位，可与 glob_files、code_symbol、read_file 在同一回合并行发出。
