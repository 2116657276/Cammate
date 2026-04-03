from __future__ import annotations

import argparse
import json

from app.services.community_service import recover_stuck_creative_jobs


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan and recover stuck creative jobs")
    parser.add_argument("--limit", type=int, default=240)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    result = recover_stuck_creative_jobs(limit=args.limit, dry_run=args.dry_run)
    print(json.dumps({"ok": True, "result": result}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
