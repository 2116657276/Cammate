from __future__ import annotations

import argparse
import json

from app.services.community_service import cleanup_expired_creative_objects


def main() -> int:
    parser = argparse.ArgumentParser(description="Cleanup expired creative objects by TTL")
    parser.add_argument("--ttl-hours", type=float, default=24.0 * 7)
    parser.add_argument("--batch", type=int, default=120)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    ttl_sec = max(3600, int(args.ttl_hours * 3600))
    result = cleanup_expired_creative_objects(ttl_sec=ttl_sec, batch=args.batch, dry_run=args.dry_run)
    print(json.dumps({"ok": True, "result": result}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
