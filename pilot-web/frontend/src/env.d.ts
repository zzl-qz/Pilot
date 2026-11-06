/// <reference types="vite/client" />

interface Window {
  pilotDesktop?: import('./types/desktop.js').DesktopBridge;

  mermaid?: {
    initialize(options: { startOnLoad: boolean; securityLevel: string; theme: string }): void;
    render(id: string, source: string): Promise<{ svg: string }>;
  };
  katex?: { render(source: string, host: HTMLElement, options: { throwOnError: boolean; displayMode: boolean }): void };
}
