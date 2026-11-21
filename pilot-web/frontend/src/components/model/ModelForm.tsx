import type { StoreState } from '../../types/store.js';
import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../../store.js';
import { buildModelUpdate } from '../../lib/model-settings.js';
import { Icon } from '../Icon.jsx';

/** 两个模型入口共用保存反馈；输入在请求期间锁定，失败保留，成功清空明文密钥。 */
export function useModelForm({ model = '', endpoint: savedEndpoint = '', contextTokens: savedContext = '', maxOutputTokens: savedOutput = '', childModel: savedChild = '' }: { model?: string; endpoint?: string; contextTokens?: string | number | null; maxOutputTokens?: string | number | null; childModel?: string }) {
  const [name, updateName] = useState(model);
  const [endpoint, updateEndpoint] = useState(savedEndpoint);
  const [key, updateKey] = useState('');
  const [contextTokens, updateContext] = useState(savedContext == null ? '' : String(savedContext));
  const [maxOutputTokens, updateOutput] = useState(savedOutput == null ? '' : String(savedOutput));
  const [childModel, updateChild] = useState(savedChild);
  const feedback = useStore(state => state.modelSave);
  const dirty = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    // 保存期间重开的表单使用响应中的新配置，保留已编辑的草稿。
    if (!dirty.current) {
      updateName(model);
      updateEndpoint(savedEndpoint);
      updateContext(savedContext == null ? '' : String(savedContext));
      updateOutput(savedOutput == null ? '' : String(savedOutput));
      updateChild(savedChild);
    }
  }, [model, savedEndpoint, savedContext, savedOutput, savedChild]);
  const edit = (setter: React.Dispatch<React.SetStateAction<string>>) => (value: string) => {
    if (useStore.getState().modelSave.busy) return;
    dirty.current = true;
    setter(value);
    useStore.getState().resetModelFeedback();
  };
  const submit = async (action: () => Promise<boolean>, resetFields = true) => {
    const success = await action();
    if (!mounted.current || !success) return false;
    const settings = useStore.getState().settingsData;
    if (resetFields && settings) {
      dirty.current = false;
      updateName(settings.model);
      updateEndpoint(settings.modelEndpoint);
      updateChild(settings.childModel || '');
    }
    updateKey('');
    return true;
  };
  return {
    name, endpoint, key, feedback, saving: feedback.busy,
    contextTokens, maxOutputTokens, childModel,
    setName: edit(updateName), setEndpoint: edit(updateEndpoint), setKey: edit(updateKey),
    setContextTokens: edit(updateContext), setMaxOutputTokens: edit(updateOutput), setChildModel: edit(updateChild),
    save: () => submit(() => useStore.getState().saveModel(buildModelUpdate({ model: name, endpoint, apiKey: key, contextTokens, maxOutputTokens, childModel }))),
    clearKey: () => submit(() => useStore.getState().clearModelKey(), false)
  };
}

export function ModelSaveFeedback({ feedback, id }: { feedback: StoreState['modelSave']; id?: string }) {
  const node = useRef<HTMLParagraphElement>(null);
  useEffect(() => { node.current?.scrollIntoView({ block: 'nearest' }); }, [feedback.message]);
  if (!feedback.message) return null;
  return <p ref={node} id={id} className={`model-save-feedback${feedback.error ? ' is-error' : ''}`} role={feedback.error ? 'alert' : 'status'}>
    <span className="model-save-indicator" aria-hidden="true">{feedback.busy ? '…' : <Icon name={feedback.error ? 'info' : 'check'} />}</span>
    <span>{feedback.message}</span>
  </p>;
}
