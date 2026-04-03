from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from app.services.community_service import cleanup_expired_creative_objects
from app.services.community_service import recover_stuck_creative_jobs


def _http_json(method: str, url: str, payload: dict[str, object] | None = None, headers: dict[str, str] | None = None):
    data = None
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    req = Request(url=url, method=method, headers=req_headers, data=data)
    with urlopen(req, timeout=6) as resp:
        raw = resp.read().decode("utf-8")
        return resp.status, json.loads(raw)


def _compose_disabled_check(base_url: str, token: str, reference_post_id: int, person_image_path: str) -> dict[str, object]:
    image_bytes = Path(person_image_path).read_bytes()
    person_b64 = base64.b64encode(image_bytes).decode("utf-8")
    payload = {
        "reference_post_id": int(reference_post_id),
        "person_image_base64": person_b64,
        "strength": 0.45,
    }
    try:
        status, _ = _http_json(
            "POST",
            f"{base_url.rstrip('/')}/community/compose",
            payload=payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        return {"ok": False, "status": status, "reason": "compose unexpectedly enabled"}
    except HTTPError as exc:
        return {"ok": exc.code == 410, "reason": f"HTTP Error {exc.code}: {exc.reason}"[:180]}
    except Exception as exc:
        return {"ok": False, "reason": str(exc)[:180]}


def _inject_service(compose_file: str, service_name: str, down_sec: float = 2.0) -> dict[str, object]:
    # TODO(p0-local): support non-docker local service injection (brew/systemctl launchctl variants).
    stop_cmd = ["docker", "compose", "-f", compose_file, "stop", service_name]
    start_cmd = ["docker", "compose", "-f", compose_file, "start", service_name]
    try:
        subprocess.run(stop_cmd, check=True, capture_output=True, text=True)
        time.sleep(max(0.5, down_sec))
        subprocess.run(start_cmd, check=True, capture_output=True, text=True)
        return {"ok": True, "service": service_name}
    except Exception as exc:
        return {"ok": False, "service": service_name, "reason": str(exc)[:200]}


def main() -> int:
    parser = argparse.ArgumentParser(description="Fault injection acceptance checks for creative runtime")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--token", default=os.getenv("CAMMATE_TEST_TOKEN", ""))
    parser.add_argument("--reference-post-id", type=int, default=int(os.getenv("CAMMATE_TEST_REFERENCE_POST_ID", "0") or 0))
    parser.add_argument("--person-image", default=os.getenv("CAMMATE_TEST_PERSON_IMAGE", ""))
    parser.add_argument("--compose-file", default="compose.yaml")
    parser.add_argument("--inject-redis-stop", action="store_true")
    parser.add_argument("--inject-minio-stop", action="store_true")
    args = parser.parse_args()

    report: dict[str, object] = {}

    status, health = _http_json("GET", f"{args.base_url.rstrip('/')}/healthz")
    queue = dict(health.get("creative_queue") or {})
    required_keys = [
        "queue_connectivity",
        "failure_codes_recent",
        "lease_risk",
        "workers_active",
        "storage_connected",
    ]
    health_keys_ok = all(key in queue for key in required_keys)
    report["healthz"] = {"ok": status == 200 and health_keys_ok, "status": status, "missing_keys": [k for k in required_keys if k not in queue]}

    report["recover_script"] = {
        "ok": True,
        "result": recover_stuck_creative_jobs(limit=200, dry_run=True),
    }
    report["cleanup_script"] = {
        "ok": True,
        "result": cleanup_expired_creative_objects(ttl_sec=3600, batch=50, dry_run=True),
    }

    if args.token and args.reference_post_id > 0 and args.person_image:
        report["api_no_heavy_compose"] = _compose_disabled_check(
            base_url=args.base_url,
            token=args.token,
            reference_post_id=args.reference_post_id,
            person_image_path=args.person_image,
        )
    else:
        report["api_no_heavy_compose"] = {
            "ok": False,
            "skipped": True,
            "reason": "set --token --reference-post-id --person-image to run compose disabled check",
        }

    if args.inject_redis_stop:
        report["inject_redis"] = _inject_service(args.compose_file, "redis")
    if args.inject_minio_stop:
        report["inject_minio"] = _inject_service(args.compose_file, "minio")

    all_checks = [bool(value.get("ok", False)) for value in report.values() if isinstance(value, dict)]
    ok = all(all_checks)
    print(json.dumps({"ok": ok, "report": report}, ensure_ascii=False, indent=2))
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
