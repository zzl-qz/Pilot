'use strict';
function outline(markdown) {
  const headings = [];
  let fence = null;
  markdown.split(/\r?\n/).forEach((line, index) => {
    const match = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (match) {
      const marker = match[1];
      if (!fence) fence = marker;
      else if (marker[0] === fence[0] && marker.length >= fence.length && !match[2].trim()) fence = null;
      return;
    }
    if (fence) return;
    const heading = line.match(/^ {0,3}(#{1,6})\s+(.+)$/);
    if (heading) headings.push({ level: heading[1].length, text: heading[2].trim(), line: index + 1 });
  });
  return headings;
}
module.exports = { outline };
