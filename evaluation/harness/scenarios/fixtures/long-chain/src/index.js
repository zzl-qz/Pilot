// 聚合入口：保持可用，导出全部模块的 key。
const ids = ['m01', 'm02', 'm03', 'm04', 'm05', 'm06', 'm07', 'm08', 'm09', 'm10', 'm11', 'm12', 'm13', 'm14', 'm15', 'm16'];
const keys = {};
for (const id of ids) keys[id] = require('./' + id + '.js').key;
module.exports = { ids, keys };
