/**
 * 点想法回填输入框时追加的文本。
 *
 * <p>输入框已有草稿就另起一行，不覆盖也不改写原草稿；草稿本身已经以换行结尾时
 * 不再多补一个空行。空草稿（包括只有空白）直接填入想法原文。
 */
export function noteInsertion(existing: string, content: string): string {
  if (!existing.trim()) return content;
  return /\n$/.test(existing) ? content : '\n' + content;
}
