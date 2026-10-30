// 测试用的最小 MCP stdio 服务器。
import { createInterface } from 'node:readline';

const tools = [
  { name: 'echo', description: 'Echo the given text back', annotations: { readOnlyHint: true },
    inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } },
  { name: 'write_note', description: 'Persist a note somewhere', inputSchema: { type: 'object', properties: { note: { type: 'string' } } } },
  { name: 'shot', description: 'Return a tiny image', annotations: { readOnlyHint: true }, inputSchema: { type: 'object' } },
  { name: 'boom', description: 'Always fails', inputSchema: { type: 'object' } }
];
// 1x1 透明 PNG
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

function reply(id, result) { process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n'); }
function fail(id, code, message) { process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n'); }

createInterface({ input: process.stdin }).on('line', (line) => {
  const message = JSON.parse(line);
  if (message.method === 'initialize') {
    return reply(message.id, { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'fake', version: '1' } });
  }
  if (message.method === 'tools/list') return reply(message.id, { tools });
  if (message.method === 'tools/call') {
    const { name, arguments: args } = message.params;
    if (name === 'echo') return reply(message.id, { content: [{ type: 'text', text: 'echo:' + args.text }] });
    if (name === 'write_note') return reply(message.id, { content: [{ type: 'text', text: 'saved ' + args.note }] });
    if (name === 'shot') return reply(message.id, { content: [{ type: 'text', text: 'here' }, { type: 'image', data: PNG, mimeType: 'image/png' }] });
    if (name === 'boom') return reply(message.id, { isError: true, content: [{ type: 'text', text: 'it broke' }] });
    return fail(message.id, -32602, 'unknown tool ' + name);
  }
  if (message.id !== undefined) fail(message.id, -32601, 'no such method');
});
