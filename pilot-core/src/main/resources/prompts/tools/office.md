创建、读回检查真实 xlsx/pptx/docx/pdf 文件，无需本机安装 Office。路径限当前工作区；创建一律用新文件名，不覆盖原件；编辑已有文件也必须另存为新文件。所有 action 先用 create_file 写 JSON 规格到 spec_path。

- create_xlsx: {"sheets":[{"name":"汇总","rows":[["项目","金额"],["A",10],["合计",{"formula":"SUM(B2:B3)"}]],"header":true,"number_format":"#,##0.00","merges":["A1:B1"]}]}
  字符串一律是文本，公式必须显式使用 {"formula":"..."}；计算失败、外部工作簿/网络公式拒绝交付。header 加粗表头，number_format 只作用于数值单元格，merges 为可选合并区域。
- create_pptx: {"slides":[{"title":"结论","bullets":["有来源的要点","下一步"],"theme":"light"}]}
  生成可编辑演示稿，每页最多七条，标题 45 字符、正文每条 90 字符；超出请拆页。theme 可选 light/dark。不要宣称这等于高级设计或视觉验收。
- create_docx: {"title":"季度报告","blocks":[{"type":"heading","text":"背景","level":1},{"type":"paragraph","text":"正文"},{"type":"bullet","text":"要点"},{"type":"table","rows":[["列A","列B"],["值1","值2"]]}],"pdf_path":"审阅稿.pdf"}
  blocks 支持 heading/paragraph/bullet/table/page_break。可选 pdf_path 用同一份内容同时产出 PDF，供 render_pdf 做视觉检查；PDF 视觉通过不等于 Word 排版一致，不能据此宣称 Word 版式已验收。
- create_pdf: {"title":"季度报告","blocks":[...],"font_path":"fonts/NotoSansSC.ttf"}
  blocks 格式与 create_docx 相同。纯 ASCII 内容不需要字体文件；含中文等非 ASCII 字符时必须能加载中文字体，优先按平台探测系统字体，也可用 font_path 指定工作区内的 .ttf/.otf/.ttc。找不到字体会明确报错并给出替代办法，不会静默输出缺字 PDF。
- edit_xlsx / edit_docx / edit_pptx: 需额外传 source_path（已有文件）和 spec_path，结果写入新的 path，绝不覆盖 source_path。
  edit_xlsx 规格 {"cells":[{"sheet":"汇总","row":3,"col":1,"value":42}],"append_rows":[{"sheet":"汇总","rows":[["新增",7]]}]}，row/col 从 0 开始。
  edit_docx 规格 {"append":[{"type":"paragraph","text":"补充段落"}]}；edit_pptx 规格 {"append_slides":[{"title":"补充","bullets":["要点"]}]}。
- inspect: 重新解析 xlsx/pptx/docx/pdf，返回工作表与单元格/公式、逐页文本、Word 段落与表格或 PDF 页数与文本。与用户输入、预期汇总和页数逐项核对。
- render_pptx / render_pdf: 生成每页 PNG，首四页回传图片；确认没有缺字、溢出和遮挡后再交付。render_pptx 的图片写在工作区，其余页可 read_file 逐页查看；render_pdf 的图片在产物目录，其余页用 pages 指定范围分段渲染，不能用 read_file 读取。

最后使用 publish_artifact（若可用）保存最终文件和来源；清楚区分结构检查、公式计算、视觉检查以及未验证项。read_file 也能直接抽取 docx/xlsx/pptx/pdf 的文本用于独立读回。

生成的真实文件留在工作区，文件工作台会自动索引、逐页预览并感知外部修改。不要为工作台另建 spec 可编辑源，也不要把产物放进私有目录导致 publish_artifact 不可达。用户在工作台留下批注时，以该文件为 source_path 用 edit_* 生成修订版，只改批注指向的部分，并报告输出路径与读回检查结果。
