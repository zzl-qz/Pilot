import assert from 'node:assert/strict';
import { test } from 'node:test';
import { publicAddress, publicHttps, externalRequestAllowed, requireExternalApproval } from '../../../pilot-core/src/main/resources/browser/browser-policy.mjs';

const publicDns = async () => [{ address: '93.184.216.34' }];
test('外网策略限制协议、私网、跨域跳转与未审批写请求', async () => {
  for (const ip of ['127.0.0.1', '10.0.0.1', '172.16.0.1', '192.168.1.1', '169.254.169.254', '::1', 'fd00::1', '::ffff:127.0.0.1', '0:0:0:0:0:0:0:1', '64:ff9b::7f00:1', '2002:7f00:1::']) assert.equal(publicAddress(ip), false, ip);
  assert.equal(publicAddress('93.184.216.34'), true);
  assert.equal(publicAddress('2606:4700:4700::1111'), true);
  for (const url of ['file:///etc/passwd', 'http://example.com', 'https://user:secret@example.com', 'https://localhost', 'https://example.com:8443']) assert.equal(await publicHttps(url, publicDns), false, url);
  assert.equal(await publicHttps('https://example.com', async () => [{address:'10.0.0.1'}]), false);
  const read = { url: 'https://example.com/jobs', origin: 'https://example.com', method: 'GET', navigation: true, actionApproved: false };
  assert.equal(await externalRequestAllowed(read, publicDns), true);
  assert.equal(await externalRequestAllowed({...read,url:'https://other.example/jobs'}, publicDns), false);
  assert.equal(await externalRequestAllowed({...read,method:'POST'}, publicDns), false);
  assert.equal(await externalRequestAllowed({...read,method:'POST',actionApproved:true}, publicDns), true);
});
test('审批绑定当前完整 URL，不接受页面变化后的旧审批', () => {
  assert.throws(() => requireExternalApproval({ expected_url: 'https://example.com/job/1' }, 'https://example.com/job/1'));
  assert.throws(() => requireExternalApproval({ approvedExternal:true, expected_url: 'https://example.com/job/1' }, 'https://example.com/job/2'));
  assert.doesNotThrow(() => requireExternalApproval({ approvedExternal:true, expected_url: 'https://example.com/job/1' }, 'https://example.com/job/1'));
});

class Node {
  constructor(tag,text='') { this.tag=tag; this.textContent=text; this.children=[]; this.dataset={}; this.attrs={}; }
  append(...children) { this.children.push(...children); }
  setAttribute(name,value) { this.attrs[name]=value; }
}
test('列表连续编号、重复1、起始编号、空行与段落边界', async () => {
  const previous = globalThis.document;
  globalThis.document = { createElement:tag=>new Node(tag), createDocumentFragment:()=>new Node('#fragment'), createTextNode:text=>new Node('#text',text) };
  try {
    const {markdownBlockRecords} = await import('../src/lib/markdown-render.js');
    for (const [input, counts] of [ ['1. A\n\n2. B\n\n3. C',[3]], ['1. A\n\n1. B\n\n1. C',[3]], ['1. A\n\n段落\n\n1. B',[1,1]], ['1. A\n\n- B',[1,1]] ]) {
      assert.deepEqual(markdownBlockRecords(input.split('\n')).filter(r=>['ol','ul'].includes(r.node.tag)).map(r=>r.node.children.length), counts);
    }
    assert.equal(markdownBlockRecords(['3. A','4. B'])[0].node.attrs.start,'3');
  } finally { globalThis.document=previous; }
});
