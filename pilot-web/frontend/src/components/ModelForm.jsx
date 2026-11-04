import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { buildModelUpdate } from '../lib/model-settings.js';

/** 两个模型入口共用保存反馈；输入在请求期间锁定，失败保留，成功清空明文密钥。 */
export function useModelForm({ model = '', endpoint: savedEndpoint = '' }) {
  const [name, updateName] = useState(model);
  const [endpoint, updateEndpoint] = useState(savedEndpoint);
  const [key, updateKey] = useState('');
  const feedback = useStore(state => state.modelSave);
  const dirty = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    // 保存期间重开的表单使用响应中的新配置，保留已编辑的草稿。
    if (!dirty.current) { updateName(model); updateEndpoint(savedEndpoint); }
  }, [model, savedEndpoint]);
  const edit = setter => value => {
    if (useStore.getState().modelSave.busy) return;
    dirty.current = true;
    setter(value);
    useStore.getState().resetModelFeedback();
  };
  const submit = async (action, resetFields = true) => {
    const success = await action();
    if (!mounted.current || !success) return false;
    const settings = useStore.getState().settingsData;
    if (resetFields) {
      dirty.current = false;
      updateName(settings.model);
      updateEndpoint(settings.modelEndpoint);
    }
    updateKey('');
    return true;
  };
  return {
    name, endpoint, key, feedback, saving: feedback.busy,
    setName: edit(updateName), setEndpoint: edit(updateEndpoint), setKey: edit(updateKey),
    save: () => submit(() => useStore.getState().saveModel(buildModelUpdate({ model: name, endpoint, apiKey: key }))),
    clearKey: () => submit(() => useStore.getState().clearModelKey(), false)
  };
}

export function ModelSaveFeedback({ feedback, id }) {
  const node = useRef(null);
  useEffect(() => { node.current?.scrollIntoView({ block: 'nearest' }); }, [feedback.message]);
  if (!feedback.message) return null;
  return <p ref={node} id={id} className={`model-save-feedback${feedback.error ? ' is-error' : ''}`} role={feedback.error ? 'alert' : 'status'}>
    <span aria-hidden="true">{feedback.busy ? '…' : feedback.error ? '!' : '✓'}</span>
    <span>{feedback.message}</span>
  </p>;
}
