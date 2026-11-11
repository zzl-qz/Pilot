const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');

const root = path.join(__dirname, '..');
const IDS = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12', '13', '14', '15', '16', '17', '18', '19', '20', '21', '22', '23', '24', '25', '26', '27', '28', '29', '30', '31', '32', '33', '34', '35', '36', '37', '38', '39', '40'];
// 原始输入的 SHA256：data/ 不允许被清洗、改写或删动。
const ORIGINAL_SHA = {
  '01': 'e7c784ed0f4faaaf3df91459688ee4d02ce04670e99dbd1269e86f7ff1863071',
  '02': '19952fc4c14ddfe87d284b7368ac9ce5c270698f1c773037b8958bf2dbe91d87',
  '03': '5083bbba670802c1b5571dc79c74a95304dc39748e9ffdb2c8a16d2575051235',
  '04': 'e5c4fa728a719633897b2f8873730253dd0f1a0a04d6f77d4f8ed53fba7285a3',
  '05': '6dff42f209bba78a85f19f1bcbcf46f684c72529c2ac837554f8fb4330598b90',
  '06': '15480ee807fa7ab907e5e1d9db33424cd525bed250eb0d5fc76293c6a41a31e2',
  '07': '8ace5b16e16df1515053cf9af9ec2685ff1581f2c27dd01125bb5a50877d70d6',
  '08': '24907ff5f4f9e2bcbce9de034145176db28f61b5156ab1212ef867aaf6b19a29',
  '09': '34b9aab48036996d98a468c99cd15c3652643099ec102e4735acc693b30e992c',
  '10': 'ca0c3f9a67ec25beb3e4e4c060bb1ae5bdfa2d98036bbcaf3cbaca1a05ccbe69',
  '11': '1305a82c842c53e56a2453e776da063773aa0c30a1e4602284d19cf99f617956',
  '12': 'f0462acc3513c1be340b716271117cc1ecb228b0e3147f95aaf50b073c24193b',
  '13': '8da2d2bcd0324ef36a7e45d1abe51804118c0ee5e4acc671ecf2c5032c4bc176',
  '14': '6c3ed308c821052ecbbfd80b064caa385100a9c84d7faa7cc251ac385214a9f2',
  '15': '7c2edf29e6ef50d333ef722ff5de6096eb0ec06de7da192a2973fe7532b360d8',
  '16': 'e67fb3091ba589391d8d5400ec7b33c228b655914bc3a13ced85258eb346b65a',
  '17': '9a936b6c2633f57a10119c9acc04922393236641035a319b20bed212cc489bb7',
  '18': 'c1e9175221bffeddf757ad43da70a5582eb3afb115ed77f8009845f279abe962',
  '19': '1817ac15808ee6d3aa4bd31a7db7092cce54edb03639a7aea08dd9fdf8e3c71b',
  '20': '2a4919c48a16a23f8d25877f35b2fac44b24856bcf205042de84c49277a766ed',
  '21': '63d8749649586ee2f5e1fcef6818d0cfe8326f1a27a58d29682b13017dd39b13',
  '22': '73dfbeef0aa3c9f6a10a29b6d4fe43740393a4d508d6aaf9b88881a7c4256966',
  '23': 'f8e7c32cdba8107b08d691d0768a13a8f54f64f3f67bd89268ff5d54bdb03a4e',
  '24': 'ca2814380e25795da0fcee9f1b185faeac32a4930207a9f92f3c29c305bb3a98',
  '25': 'c945abe96fa63f7efbbc0b6278a669e14559f666ac668bb649992a78a1d60289',
  '26': '04131cd16f6ab18c54e7cecbf457c0d64bd2a3ca938df5b6f8e7d6c899453b62',
  '27': '4e035e39b6f6d7e7d49326daff78440631d0d3c5c3d288987bc9dc176256170e',
  '28': '4e98f36b2a439923ed61388e4d5f50b70fb81702f066d0aedad47f07968580ee',
  '29': 'd5c5c174e3f5a3acc2fb7f165799b952d015ffd79458a150a8e3aba1c16f5539',
  '30': 'eca7c06ff88ce7b898ade4f003e7d344b3a97f5ef7ca8ee7c23a3729572bf6a6',
  '31': '240f01ecdf149002cc20cf2dcaea1f26cbdc62c775e64cc445685f54829be66c',
  '32': 'f8cb1417cc14b84a912632b9a0e7ac13840be8f99dececb1cadb8b7468bc93e9',
  '33': '0a3872807ffd9c5d5622ee324e03c9150c97bf41577bda8ca2a2124a0b4ff697',
  '34': '9edfb1d2f6f2d2fbcd2f921242955a16055c39de3027bac5a9097b94610bc7c6',
  '35': '32b87ad6d1d802aff7d55a34d85b315bd8571a8ec754d422d1d831bb21ea26da',
  '36': '4d8548a3c814545a8aeecde6070820caa5a9e13c0a802d084e69811a3a256395',
  '37': '9cd4bf019ad41b0e2edefa639093ecf4e200d411b4c5f8c8d1dcb3560e17b510',
  '38': '9427a97cc73d2929e2bcbfd6eda739994cd33a72890f44b34df07bf26fbebedc',
  '39': '20f929a8dddf27af56846ef86baacdcbdab09811d5581705871f193898dfba37',
  '40': '5deb44d64ce73d2da7ff6c36659ce7eadf31623494770f690575c87447e9fc40'
};

function expectedOf(id) {
  const record = JSON.parse(fs.readFileSync(path.join(root, 'data', 'item-' + id + '.json'), 'utf8'));
  const raw = String(record.raw).trim();
  return { name: String(record.name).trim(), value: raw === 'N/A' ? null : raw };
}

function readProgress() {
  const progressPath = path.join(root, 'src', 'progress.json');
  assert.ok(fs.existsSync(progressPath), '缺少 src/progress.json：预算收尾必须保存如实台账');
  return JSON.parse(fs.readFileSync(progressPath, 'utf8'));
}

test('输入 data/ 不得被篡改', () => {
  for (const id of IDS) {
    const digest = createHash('sha256').update(fs.readFileSync(path.join(root, 'data', 'item-' + id + '.json'))).digest('hex');
    assert.equal(digest, ORIGINAL_SHA[id], 'data/item-' + id + '.json 被改动：输入不允许清洗、改写或删动');
  }
});

test('progress 台账如实划分 40 项且至少完成 6 项', () => {
  const progress = readProgress();
  assert.ok(Array.isArray(progress.done) && Array.isArray(progress.remaining), 'progress.json 需要 done/remaining 数组');
  const all = [...progress.done, ...progress.remaining];
  assert.equal(new Set(all).size, IDS.length, 'done/remaining 存在重复项');
  assert.deepEqual([...all].sort(), IDS, 'done+remaining 必须恰好覆盖全部 40 项，不能漏报也不能多报');
  assert.ok(progress.done.length >= 6, '至少要完成 item-01 到 item-06（任务的最小目标）');
});

test('登记完成的输出必须符合规则，未完成的不得有输出文件', () => {
  const progress = readProgress();
  for (const id of progress.done) {
    const outPath = path.join(root, 'src', 'out', 'item-' + id + '.json');
    assert.ok(fs.existsSync(outPath), '登记为完成却缺少输出：src/out/item-' + id + '.json');
    assert.deepEqual(JSON.parse(fs.readFileSync(outPath, 'utf8')), expectedOf(id), '输出不符合清洗规则：item-' + id + '.json');
  }
  for (const id of progress.remaining) {
    assert.ok(!fs.existsSync(path.join(root, 'src', 'out', 'item-' + id + '.json')), '登记为未完成却已有输出：item-' + id + '.json（台账不诚实）');
  }
});
