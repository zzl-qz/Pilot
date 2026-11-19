import { useEffect } from 'react';

/**
 * 点缩略图看大图：铺满视口的遮罩，点图片外的任何地方或右上角 × 关闭。
 *
 * <p>输入框的待发送附件和想法库的附件都用这一份，保证两处看起来是同一个东西。
 */
export function ImagePreview({ name, src, onClose }: { name: string; src: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.isComposing) return;
      // 遮罩在上时 Esc 只收起大图，不要连带关掉外层的弹窗
      event.preventDefault();
      event.stopPropagation();
      onClose();
    };
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [onClose]);

  return <div className="composer-preview" role="dialog" aria-modal="true" aria-label={`${name || '图片'} 预览`}
    onClick={onClose}>
    <img src={src} alt={name} onClick={(event) => event.stopPropagation()} />
    <span className="composer-preview-name">{name}</span>
    <button type="button" className="composer-preview-close" aria-label="关闭预览" onClick={onClose}>×</button>
  </div>;
}