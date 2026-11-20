#!/usr/bin/env python3
"""Pilot 自举训练监督脚本（跨平台，仅依赖 Python 标准库）。

支持：
- 指定隔离任务 worktree（--workspace），默认拒绝指向主工作区 ~/Desktop/pilot
- 强制 dogfood: 会话标题前缀，避免与用户日常会话混淆
- 记录脱敏实验指纹（runnerSha / baseSha / model / endpointHost / permissionMode，零密钥）
- 全流程操作：start / send / continue / steer / answer / cancel / watch / diagnostics / fingerprint / dump
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_BASE = os.environ.get("PILOT_BASE_URL", "http://127.0.0.1:9999")
DEFAULT_MAIN_REPO = str(Path(os.environ.get("PILOT_MAIN_REPO", Path.home() / "Desktop" / "pilot")).resolve())
DEFAULT_RUNNER_DIR = str(Path(os.environ.get("PILOT_RUNNER_DIR", Path.home() / "pilot-dogfood" / "runner")).resolve())


def http_json(base: str, method: str, path: str, body=None, timeout: int = 30):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    req = urllib.request.Request(base.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code} {method} {path}: {detail}") from e


def git_rev(repo_dir: str) -> str:
    try:
        out = subprocess.check_output(
            ["git", "-C", repo_dir, "rev-parse", "HEAD"],
            stderr=subprocess.DEVNULL,
            text=True,
        )
        return out.strip()
    except Exception:
        return ""


def sanitize_endpoint(raw: str) -> str:
    if not raw:
        return ""
    parsed = urllib.parse.urlparse(raw)
    if not parsed.scheme or not parsed.netloc:
        return "(invalid)"
    host = parsed.hostname or ""
    port = f":{parsed.port}" if parsed.port else ""
    return f"{parsed.scheme}://{host}{port}{parsed.path}"


def build_fingerprint(base: str, workspace: str, runner_dir: str, extra: dict = None) -> dict:
    settings = http_json(base, "GET", "/api/settings") or {}
    fp = {
        "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "baseUrl": base,
        "workspace": str(Path(workspace).resolve()) if workspace else "",
        "baseSha": git_rev(workspace) if workspace else "",
        "runnerDir": str(Path(runner_dir).resolve()) if runner_dir else "",
        "runnerSha": git_rev(runner_dir) if runner_dir else "",
        "model": settings.get("model") or settings.get("modelName") or "",
        "endpoint": sanitize_endpoint(settings.get("endpoint") or settings.get("modelEndpoint") or ""),
        "modelApiKeySet": bool(settings.get("apiKeySet") if "apiKeySet" in settings else settings.get("modelApiKeySet")),
        "permissionMode": settings.get("permissionMode") or "",
    }
    if extra:
        fp.update(extra)
    return fp


def load_text(text: str, file_path: str) -> str:
    if file_path:
        return Path(file_path).read_text(encoding="utf-8")
    if text is not None:
        return text
    raise ValueError("必须提供 --prompt 或 --prompt-file")


def summarize_event(ev: dict):
    seq = ev.get("sequence")
    et = ev.get("type") or ev.get("eventType")
    p = ev.get("payload") or {}
    if not et and isinstance(p, dict):
        et = p.get("type")
    if et in ("assistant.delta", "AssistantDelta", "reasoning.delta"):
        return None
    if et == "run.started":
        return f"[{seq}] RUN_START: {str(p.get('input', ''))[:140]}"
    if et == "run.steered":
        return f"[{seq}] RUN_STEERED: {str(p.get('input') or p.get('content') or '')[:140]}"
    if et == "run.continued":
        return f"[{seq}] RUN_CONTINUED: {json.dumps(p, ensure_ascii=False)[:180]}"
    if et == "tool.started":
        name = p.get("name") or p.get("tool")
        cid = p.get("callId", "")
        summary = p.get("summary") or json.dumps(p.get("arguments") or p.get("args") or {}, ensure_ascii=False)
        return f"[{seq}] TOOL_START {name} ({cid}) {str(summary)[:220]}"
    if et == "tool.completed":
        name = p.get("name") or p.get("tool") or ""
        cid = p.get("callId", "")
        out = str(p.get("resultSummary") or p.get("output") or p.get("summary") or "").replace("\n", "\\n")
        ms = p.get("latencyMs", "")
        return f"[{seq}] TOOL_DONE  {name} ({cid}) {ms}ms out={out[:220]}"
    if et in ("question.asked", "user.question", "ask_user"):
        return f"[{seq}] *** ASK_USER *** {json.dumps(p, ensure_ascii=False)}"
    if et == "model.request":
        rep = p.get("report") or p
        usage = rep.get("usage") or {}
        prefix = rep.get("prefix") or {}
        inp = usage.get("inputTokens", 0)
        cached = usage.get("cachedInputTokens", 0)
        hit = f"{(cached / inp * 100):.1f}%" if inp else "n/a"
        return (
            f"[{seq}] MODEL_REQ purpose={rep.get('purpose')} status={rep.get('status')} "
            f"in={inp} cached={cached}({hit}) out={usage.get('outputTokens', 0)} "
            f"prefix={prefix.get('change')} ms={rep.get('durationMs')}"
        )
    if et == "run.usage":
        return None
    if et == "context.compacted":
        return (
            f"[{seq}] COMPACTED covered={p.get('coveredMessages')} "
            f"chars={p.get('charactersBefore')}->{p.get('charactersAfter')}"
        )
    if et == "plan.updated":
        items = p.get("items") or p.get("plan") or []
        brief = " | ".join(f"{i.get('status')}:{i.get('step') or i.get('content')}" for i in items)
        return f"[{seq}] PLAN ({len(items)}): {brief[:240]}"
    if et in ("run.completed", "run.failed", "run.cancelled"):
        return f"[{seq}] {et.upper()}: {json.dumps(p, ensure_ascii=False)[:320]}"
    return f"[{seq}] {et}: {json.dumps(p, ensure_ascii=False)[:220]}"


def watch_session(base: str, sid: str, timeout_s: int, after: int, poll_s: float):
    deadline = time.time() + timeout_s
    seen_any = after > 0
    while time.time() < deadline:
        hist = http_json(base, "GET", f"/api/sessions/{sid}/history?limit=200&after={after}") or {}
        for ev in hist.get("events", []):
            seen_any = True
            after = max(after, int(ev.get("sequence", after)))
            line = summarize_event(ev)
            if line:
                print(line, flush=True)
        active = http_json(base, "GET", f"/api/sessions/{sid}/runs/active") or {}
        if not active.get("runId") and seen_any:
            time.sleep(0.5)
            hist = http_json(base, "GET", f"/api/sessions/{sid}/history?limit=200&after={after}") or {}
            for ev in hist.get("events", []):
                after = max(after, int(ev.get("sequence", after)))
                line = summarize_event(ev)
                if line:
                    print(line, flush=True)
            print(f"=== RUN FINISHED (sessionId={sid}, lastSeq={after}) ===", flush=True)
            return 0
        time.sleep(poll_s)
    print(f"=== WATCH TIMEOUT (sessionId={sid}, lastSeq={after}) ===", flush=True)
    return 124


def main():
    parser = argparse.ArgumentParser(description="Pilot dogfood supervisor CLI")
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--runner-dir", default=DEFAULT_RUNNER_DIR)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_start = sub.add_parser("start", help="创建 dogfood 会话并下发任务")
    p_start.add_argument("--title", required=True)
    p_start.add_argument("--workspace", required=True)
    p_start.add_argument("--prompt")
    p_start.add_argument("--prompt-file")
    p_start.add_argument("--fingerprint-out")
    p_start.add_argument("--allow-main-repo", action="store_true")

    p_send = sub.add_parser("send", help="向已有会话发送追加消息")
    p_send.add_argument("session_id")
    p_send.add_argument("--prompt")
    p_send.add_argument("--prompt-file")

    p_cont = sub.add_parser("continue", help="对已有会话触发 /continue 续跑")
    p_cont.add_argument("session_id")

    p_steer = sub.add_parser("steer", help="向运行中的会话发送 /steer 纠偏")
    p_steer.add_argument("session_id")
    p_steer.add_argument("content")

    p_ans = sub.add_parser("answer", help="回答 ask_user 问题")
    p_ans.add_argument("session_id")
    p_ans.add_argument("call_id")
    p_ans.add_argument("choice")

    p_cancel = sub.add_parser("cancel", help="立即取消运行中的 run")
    p_cancel.add_argument("run_id")

    p_watch = sub.add_parser("watch", help="实时监控会话事件直到本次 run 结束")
    p_watch.add_argument("session_id")
    p_watch.add_argument("--timeout", type=int, default=1800)
    p_watch.add_argument("--after", type=int, default=0)
    p_watch.add_argument("--poll", type=float, default=2.0)

    p_diag = sub.add_parser("diagnostics", help="拉取 /diagnostics")
    p_diag.add_argument("session_id")

    p_fp = sub.add_parser("fingerprint", help="输出脱敏实验指纹 JSON")
    p_fp.add_argument("--workspace", required=True)

    p_dump = sub.add_parser("dump", help="打印会话完整精简事件流")
    p_dump.add_argument("session_id")

    args = parser.parse_args()

    if args.cmd == "start":
        ws = str(Path(args.workspace).resolve())
        if not args.allow_main_repo and ws == DEFAULT_MAIN_REPO:
            raise SystemExit(f"拒绝直接指向主工作区 {DEFAULT_MAIN_REPO}，请使用 ~/pilot-dogfood/ws-Txxx")
        if not os.path.isdir(ws):
            raise SystemExit(f"工作区目录不存在: {ws}")
        title = args.title if args.title.startswith("dogfood:") else f"dogfood: {args.title}"
        prompt = load_text(args.prompt, args.prompt_file)
        session = http_json(args.base, "POST", "/api/sessions", {"title": title, "workspace": ws})
        sid = session["id"]
        msg = http_json(args.base, "POST", f"/api/sessions/{sid}/messages", {"content": prompt})
        fp = build_fingerprint(args.base, ws, args.runner_dir, {"sessionId": sid, "runId": msg["runId"], "title": title})
        if args.fingerprint_out:
            out_path = Path(args.fingerprint_out)
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_text(json.dumps(fp, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(fp, ensure_ascii=False, indent=2))
    elif args.cmd == "send":
        prompt = load_text(args.prompt, args.prompt_file)
        msg = http_json(args.base, "POST", f"/api/sessions/{args.session_id}/messages", {"content": prompt})
        print(json.dumps({"sessionId": args.session_id, "runId": msg.get("runId")}, ensure_ascii=False))
    elif args.cmd == "continue":
        msg = http_json(args.base, "POST", f"/api/sessions/{args.session_id}/continue", {})
        print(json.dumps(msg, ensure_ascii=False))
    elif args.cmd == "steer":
        msg = http_json(args.base, "POST", f"/api/sessions/{args.session_id}/steer", {"content": args.content})
        print(json.dumps(msg, ensure_ascii=False))
    elif args.cmd == "answer":
        msg = http_json(
            args.base,
            "POST",
            f"/api/sessions/{args.session_id}/questions/{args.call_id}",
            {"choice": args.choice},
        )
        print(json.dumps(msg, ensure_ascii=False))
    elif args.cmd == "cancel":
        msg = http_json(args.base, "POST", f"/api/runs/{args.run_id}/cancel", {})
        print(json.dumps(msg, ensure_ascii=False))
    elif args.cmd == "watch":
        sys.exit(watch_session(args.base, args.session_id, args.timeout, args.after, args.poll))
    elif args.cmd == "diagnostics":
        diag = http_json(args.base, "GET", f"/api/sessions/{args.session_id}/diagnostics")
        print(json.dumps(diag, ensure_ascii=False, indent=2))
    elif args.cmd == "fingerprint":
        fp = build_fingerprint(args.base, args.workspace, args.runner_dir)
        print(json.dumps(fp, ensure_ascii=False, indent=2))
    elif args.cmd == "dump":
        after = 0
        while True:
            hist = http_json(args.base, "GET", f"/api/sessions/{args.session_id}/history?limit=500&after={after}") or {}
            evs = hist.get("events", [])
            if not evs:
                break
            for ev in evs:
                line = summarize_event(ev)
                if line:
                    print(line)
            after = int(evs[-1]["sequence"])
            if len(evs) < 500:
                break


if __name__ == "__main__":
    main()
