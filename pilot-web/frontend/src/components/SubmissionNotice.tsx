import { useEffect, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { submissions, watchSubmissions, type PendingSubmission } from '../lib/submissions.js';
import { errorInfo } from '../lib/errors.js';
import { useStore } from '../store.js';

/** 凭据跨刷新保留；查询按钮只发 GET，解除阻止也不会发送草稿。 */
export function SubmissionNotice({ sessionId }: { sessionId: string | null }) {
  const [pending, setPending] = useState<PendingSubmission[]>([]);
  const [error, setError] = useState('');
  const [checking, setChecking] = useState(false);
  useEffect(() => {
    const refresh = () => {
      try { setPending(sessionId ? submissions.pending(sessionId) : []); setError(''); }
      catch (e) { setError(errorInfo(e).message || '无法读取提交凭据'); }
    };
    refresh();
    return watchSubmissions(refresh);
  }, [sessionId]);
  async function check() {
    setChecking(true);
    try {
      for (const record of pending) {
        const result = await submissions.check(record);
        useStore.getState().showNotice(result.state === 'accepted' ? '已确认消息被接收。草稿保留供核对，请勿重复发送。'
          : result.state === 'rejected' ? `消息被拒绝：${result.message || '请修改草稿后重试。'}` : result.message || '提交结果仍待确认。');
      }
      if (useStore.getState().sessionId === sessionId) await useStore.getState().refreshWorkbench();
    } finally { setChecking(false); }
  }
  if (!pending.length && !error) return null;
  return <div className="submission-notice" role="status">
    <span>{error || `有 ${pending.length} 条提交结果待确认，任务可能已经执行。草稿已保留，请先核对任务历史和队列。`}</span>
    {!!pending.length && <>
      <span title={pending.map(item => item.preview).join('\n')}>上次提交：{pending[0].preview}</span>
      <div>
        <Button size="sm" variant="ghost" label="查询提交结果" isDisabled={checking} onClick={() => void check()} />
        <Button size="sm" variant="ghost" label="已核对历史" isDisabled={checking} onClick={() => useStore.getState().askConfirm({
          title: '解除待确认状态', message: '请先检查任务历史和消息队列。解除后可以再次发送，若原消息已执行，再次发送会重复执行。此操作不会自动发送草稿。',
          confirmText: '已核对，解除阻止', danger: true,
          onConfirm: () => { try { pending.forEach(record => submissions.forget(record)); } catch (e) { setError(errorInfo(e).message || '无法清理提交凭据'); } }
        })} />
      </div>
    </>}
  </div>;
}
