from __future__ import annotations

import argparse
import json
from urllib.error import URLError
from urllib.request import urlopen


def fetch_health(base_url: str) -> dict[str, object]:
    url = f"{base_url.rstrip('/')}/healthz"
    with urlopen(url, timeout=3) as response:
        raw = response.read().decode("utf-8")
    return json.loads(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description="Check creative runtime alerts from /healthz")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--failed-recent-warn", type=int, default=8)
    parser.add_argument("--retry-scheduled-warn", type=int, default=20)
    args = parser.parse_args()

    critical: list[str] = []
    warning: list[str] = []

    try:
        health = fetch_health(args.base_url)
    except URLError as exc:
        critical.append(f"healthz_unreachable:{exc}")
        print(json.dumps({"ok": False, "critical": critical, "warning": warning}, ensure_ascii=False, indent=2))
        return 2

    queue = dict(health.get("creative_queue") or {})
    connected = bool(queue.get("connected", False))
    storage_connected = bool(queue.get("storage_connected", False))
    running = int(queue.get("running", 0) or 0)
    workers_active = int(queue.get("workers_active", queue.get("workers", 0)) or 0)
    lease_risk = dict(queue.get("lease_risk") or {})
    failed_recent = int(queue.get("failed_recent", 0) or 0)
    retry_scheduled = int(queue.get("retry_scheduled", 0) or 0)

    if not connected:
        critical.append("redis_queue_disconnected")
    if not storage_connected:
        critical.append("object_storage_disconnected")
    if running > 0 and workers_active <= 0:
        critical.append("running_jobs_without_active_workers")
    if int(lease_risk.get("critical", 0) or 0) > 0:
        critical.append("lease_critical_risk")

    if int(lease_risk.get("warning", 0) or 0) > 0:
        warning.append("lease_warning_risk")
    if failed_recent >= max(1, args.failed_recent_warn):
        warning.append("failed_recent_high")
    if retry_scheduled >= max(1, args.retry_scheduled_warn):
        warning.append("retry_scheduled_high")

    ok = len(critical) == 0 and len(warning) == 0
    print(
        json.dumps(
            {
                "ok": ok,
                "critical": critical,
                "warning": warning,
                "creative_queue": queue,
            },
            ensure_ascii=False,
            indent=2,
        )
    )

    if critical:
        return 2
    if warning:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
