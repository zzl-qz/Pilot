import React from 'react';

const paths = {
  branch: 'M4 5v6 M12 5v2a4 4 0 0 1-4 4H6 M4 1a2 2 0 1 0 0 4 2 2 0 0 0 0-4 M12 1a2 2 0 1 0 0 4 2 2 0 0 0 0-4 M4 11a2 2 0 1 0 0 4 2 2 0 0 0 0-4',
  mic: 'M6 3a2 2 0 0 1 4 0v5a2 2 0 0 1-4 0Z M3.5 7v1a4.5 4.5 0 0 0 9 0V7 M8 12.5V15 M5.5 15h5',
  steer: 'M3 3v3a3 3 0 0 0 3 3h7 m-3-3 3 3-3 3',
  edit: 'm10 2 4 4-8 8H2v-4Z M8 4l4 4',
  trash: 'M2 4h12 M6 4V2h4v2 M4 4l1 10h6l1-10 M7 7v4 M9 7v4',
  more: 'M3 8h.01 M8 8h.01 M13 8h.01',
  chevron: 'm6 3 5 5-5 5',
  folder: 'M2 4h4l2 2h6v7H2Z M2 4V3h4l2 2h6v1',
  folderOpen: 'M2 6V3h4l2 2h6v2 M2 7h13l-2 6H2Z',
  file: 'M4 2h5l3 3v9H4Z M9 2v4h3',
  code: 'm5 5-3 3 3 3 m6-6 3 3-3 3 M9 3 7 13',
  image: 'M2 2h12v12H2Z m0 9 4-4 3 3 2-2 3 3 M10 5h.01',
  refresh: 'M13 7a5 5 0 1 0 0 3 M13 2v5H8',
  close: 'm4 4 8 8 M12 4l-8 8',
  search: 'M11 11l3 3 M12 7a5 5 0 1 1-10 0 5 5 0 0 1 10 0',
  check: 'm3 8 3 3 7-7',
  palette: 'M14 8a6 6 0 1 0-6 6h1a2 2 0 0 0 0-4h3a2 2 0 0 0 2-2 M5 5h.01 M9 4h.01 M4 8h.01',
  model: 'M5 2h6v3h3v6h-3v3H5v-3H2V5h3Z M6 6h4v4H6Z',
  shield: 'M8 2 3 4v4c0 3 5 6 5 6s5-3 5-6V4Z m-3 6 2 2 3-4',
  skill: 'm8 1 2 5 5 2-5 2-2 5-2-5-5-2 5-2Z',
  chart: 'M2 2v12h12 M5 11V7 M8 11V3 M11 11V5',
  pulse: 'M1 8h3l2-5 4 10 2-5h3',
  info: 'M8 7v5 M8 4h.01 M14 8A6 6 0 1 1 2 8a6 6 0 0 1 12 0',
};
export function Icon({ name, className = '' }) {
  return <svg className={`ui-icon ${className}`} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name] || paths.file} /></svg>;
}

export function FileIcon({ name = '', directory, open }) {
  const icon = directory ? (open ? 'folderOpen' : 'folder') : /\.(png|jpe?g|gif|webp|svg|ico)$/i.test(name) ? 'image' : /\.(jsx?|tsx?|java|py|json|xml|html|css|sh|ya?ml)$/i.test(name) ? 'code' : 'file';
  return <Icon name={icon} className={`file-kind ${directory ? 'is-folder' : ''}`} />;
}
