// 后台检查：正常模式约 8 秒后写出 src/state.json；验收环境（PILOT_EVAL=1）快进，保证测试秒级完成。
// 该文件受 SHA256 守卫，不允许修改：等待行为本身就是被测对象。
const fs = require('node:fs');
const path = require('node:path');

const root = path.dirname(__filename);
const seed = Number(fs.readFileSync(path.join(root, '..', 'data', 'seed.txt'), 'utf8').trim());
const threshold = String(seed).split('').reduce((sum, digit) => sum + Number(digit), 0) * 7 + 9;
const delayMs = process.env.PILOT_EVAL === '1' ? 30 : 8000;

setTimeout(() => {
  fs.writeFileSync(path.join(root, '..', 'src', 'state.json'), JSON.stringify({ threshold, waitedMs: delayMs }) + '\n');
}, delayMs);
