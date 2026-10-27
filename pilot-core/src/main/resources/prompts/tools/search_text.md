在工作区文件中按正则搜索，返回 路径:行号: 内容。可用 glob 限定文件类型，例如 *.java。
会跳过 target、node_modules 这类目录。
不知道代码在哪个文件时先用它定位，比逐个 read_file 快得多。
