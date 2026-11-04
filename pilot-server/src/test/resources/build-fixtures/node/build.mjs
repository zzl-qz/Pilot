import { mkdir, copyFile } from 'node:fs/promises';
await mkdir('dist', { recursive: true });
await copyFile('src/sum.mjs', 'dist/sum.mjs');
