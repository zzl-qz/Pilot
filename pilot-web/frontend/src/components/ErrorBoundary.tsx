import React from 'react';
import { errorInfo } from '../lib/errors.js';
type BoundaryProps = {
  name?: string;
  children?: React.ReactNode;
  onReset?: () => void;
  fallback?: React.ReactNode;
};
type BoundaryState = { error: unknown };

/**
 * 局部错误边界：某个面板渲染炸了只废掉它自己，不要把整棵 React 树卸载成白屏。
 *
 * <p>没有它的时候，右栏「变更」里一个 TypeError 就会让 #root 变空——
 * 页面上什么都没了，日志也只留在控制台里，用户只能看到白屏。
 */
export class ErrorBoundary extends React.Component<BoundaryProps, BoundaryState> {
  constructor(props: BoundaryProps) {
    super(props);
    this.state = { error: null };
    this.reset = this.reset.bind(this);
  }

  static getDerivedStateFromError(error: unknown) {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error(`[ui] ${this.props.name || 'panel'} 渲染失败`, error, info?.componentStack || '');
  }

  reset() {
    this.setState({ error: null });
    this.props.onReset?.();
  }

  render() {
    const { error } = this.state;
    if (!error) {
      return this.props.children;
    }
    if (this.props.fallback) return this.props.fallback;
    return (
      <div className="panel-error" role="alert">
        <p className="panel-error-title">{this.props.name || '这一块'}渲染失败了</p>
        <pre className="panel-error-detail">{String(errorInfo(error).message || error)}</pre>
        <button type="button" className="btn" onClick={this.reset}>重试</button>
      </div>
    );
  }
}
