from __future__ import annotations

import argparse
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PY_ROOT = ROOT / "py"
DEFAULT_DB_PATH = PY_ROOT / "demo_app_data.db"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the CamMate API server and safely restart an existing process on the same port.",
    )
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8010)
    parser.add_argument("--app", default="main:app")
    parser.add_argument("--app-dir", default=str(PY_ROOT))
    parser.add_argument("--db-path", default=str(DEFAULT_DB_PATH))
    parser.add_argument("--grace-sec", type=float, default=3.0)
    parser.add_argument(
        "--no-restart",
        action="store_true",
        help="Fail instead of terminating the existing listener on the same port.",
    )
    return parser.parse_args()


def _resolve_path(raw: str) -> str:
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = ROOT / path
    return str(path.resolve())


def _listening_pids(port: int) -> list[int]:
    result = subprocess.run(
        ["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode not in {0, 1}:
        raise RuntimeError(result.stderr.strip() or "failed to inspect listening process")
    pids: list[int] = []
    for line in result.stdout.splitlines():
        value = line.strip()
        if not value:
            continue
        try:
            pid = int(value)
        except ValueError:
            continue
        if pid != os.getpid():
            pids.append(pid)
    return sorted(set(pids))


def _read_cmdline(pid: int) -> str:
    result = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip()


def _pid_exists(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _port_is_open(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.2)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def _terminate_pid(pid: int, grace_sec: float) -> None:
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.time() + max(0.5, grace_sec)
    while time.time() < deadline:
        if not _pid_exists(pid):
            return
        time.sleep(0.1)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    deadline = time.time() + 1.5
    while time.time() < deadline:
        if not _pid_exists(pid):
            return
        time.sleep(0.05)


def _restart_existing_listener(port: int, grace_sec: float) -> None:
    pids = _listening_pids(port)
    if not pids:
        return
    print(f"[api-runner] port {port} already in use, restarting existing listener", flush=True)
    for pid in pids:
        command = _read_cmdline(pid)
        print(f"[api-runner] stopping pid={pid} cmd={command or '<unknown>'}", flush=True)
        _terminate_pid(pid, grace_sec=grace_sec)
    deadline = time.time() + max(1.0, grace_sec + 1.0)
    while time.time() < deadline:
        if not _port_is_open(port):
            return
        time.sleep(0.1)
    raise RuntimeError(f"port {port} is still occupied after restart attempt")


def main() -> int:
    args = parse_args()
    if str(PY_ROOT) not in sys.path:
        sys.path.insert(0, str(PY_ROOT))

    os.environ["APP_DB_PATH"] = _resolve_path(args.db_path)
    app_dir = _resolve_path(args.app_dir)

    pids = _listening_pids(args.port)
    if pids:
        if args.no_restart:
            details = "; ".join(
                f"pid={pid} cmd={_read_cmdline(pid) or '<unknown>'}"
                for pid in pids
            )
            print(f"[api-runner] port {args.port} already in use: {details}", file=sys.stderr)
            return 1
        _restart_existing_listener(port=args.port, grace_sec=args.grace_sec)

    print(
        f"[api-runner] starting host={args.host} port={args.port} "
        f"app={args.app} db={os.environ['APP_DB_PATH']}",
        flush=True,
    )

    import uvicorn

    uvicorn.run(
        args.app,
        app_dir=app_dir,
        host=args.host,
        port=args.port,
        reload=False,
        access_log=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
