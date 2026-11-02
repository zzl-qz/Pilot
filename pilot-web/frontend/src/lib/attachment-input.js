/** 服务端消息接口接收 dataBase64；读取阶段不创建会话或上传。 */
export function readAttachmentFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error(`无法读取「${file.name}」`));
    reader.onabort = () => reject(new Error(`已取消读取「${file.name}」`));
    reader.onload = () => resolve({
      id: crypto.randomUUID(), name: file.name || 'clipboard.png',
      mimeType: file.type || 'application/octet-stream', size: file.size,
      dataBase64: reader.result
    });
    reader.readAsDataURL(file);
  });
}
