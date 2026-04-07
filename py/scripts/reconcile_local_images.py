from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
PY_ROOT = SCRIPT_DIR.parent
if str(PY_ROOT) not in sys.path:
    sys.path.insert(0, str(PY_ROOT))

from app.core.config import PROJECT_ROOT
from app.core.config import SETTINGS
from app.core.database import open_db


@dataclass(frozen=True)
class DbImageRef:
    table: str
    pk_name: str
    pk_value: int
    column: str
    raw_path: str
    resolved_path: Path
    exists: bool


@dataclass(frozen=True)
class PathRewrite:
    ref: DbImageRef
    canonical_raw_path: str
    canonical_resolved_path: Path


_TABLE_PK = {
    "community_posts": "id",
    "community_creative_jobs": "id",
}

_FIELD_PRIORITY = {
    ("community_posts", "image_path"): 0,
    ("community_creative_jobs", "result_image_path"): 1,
    ("community_creative_jobs", "compare_input_path"): 2,
}


def _resolve_raw_path(raw_path: str) -> Path | None:
    raw = str(raw_path or "").strip()
    if not raw:
        return None
    path = Path(raw)
    return path.resolve() if path.is_absolute() else (PROJECT_ROOT / path).resolve()


def _is_under_root(path: Path, root: Path) -> bool:
    return path == root or root in path.parents


def _mutable_roots() -> list[Path]:
    return [
        SETTINGS.community_upload_dir.resolve(),
        SETTINGS.community_creative_result_dir.resolve(),
    ]


def _is_mutable_path(path: Path) -> bool:
    return any(_is_under_root(path, root) for root in _mutable_roots())


def _scan_filesystem_files() -> list[Path]:
    files: list[Path] = []
    for root in _mutable_roots():
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if path.is_file():
                files.append(path.resolve())
    return sorted(set(files))


def _load_db_refs() -> list[DbImageRef]:
    conn = open_db()
    try:
        rows: list[DbImageRef] = []
        rows.extend(_load_table_refs(conn, "community_posts", ["image_path"]))
        rows.extend(_load_table_refs(conn, "community_creative_jobs", ["result_image_path", "compare_input_path"]))
        return rows
    finally:
        conn.close()


def _load_table_refs(conn: Any, table: str, columns: list[str]) -> list[DbImageRef]:
    pk_name = _TABLE_PK[table]
    select_cols = ", ".join([pk_name, *columns])
    db_rows = conn.execute(f"SELECT {select_cols} FROM {table}").fetchall()
    refs: list[DbImageRef] = []
    for row in db_rows:
        pk_value = int(row[pk_name])
        for column in columns:
            raw_path = str(row[column] or "").strip()
            if not raw_path:
                continue
            resolved_path = _resolve_raw_path(raw_path)
            if resolved_path is None:
                continue
            refs.append(
                DbImageRef(
                    table=table,
                    pk_name=pk_name,
                    pk_value=pk_value,
                    column=column,
                    raw_path=raw_path,
                    resolved_path=resolved_path,
                    exists=resolved_path.exists(),
                )
            )
    return refs


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _choose_canonical_ref(group: list[DbImageRef]) -> DbImageRef:
    def sort_key(ref: DbImageRef) -> tuple[int, int, str, str]:
        field_priority = _FIELD_PRIORITY.get((ref.table, ref.column), 99)
        return (field_priority, ref.pk_value, ref.raw_path, str(ref.resolved_path))

    return sorted(group, key=sort_key)[0]


def _plan_rewrites(refs: list[DbImageRef]) -> list[PathRewrite]:
    mutable_existing_refs = [ref for ref in refs if ref.exists and _is_mutable_path(ref.resolved_path)]
    by_sha: dict[str, list[DbImageRef]] = {}
    for ref in mutable_existing_refs:
        by_sha.setdefault(_hash_file(ref.resolved_path), []).append(ref)

    rewrites: list[PathRewrite] = []
    for group in by_sha.values():
        unique_paths = {str(ref.resolved_path) for ref in group}
        if len(unique_paths) <= 1:
            continue
        canonical = _choose_canonical_ref(group)
        for ref in group:
            if ref.resolved_path == canonical.resolved_path:
                continue
            rewrites.append(
                PathRewrite(
                    ref=ref,
                    canonical_raw_path=canonical.raw_path,
                    canonical_resolved_path=canonical.resolved_path,
                )
            )
    return rewrites


def _apply_rewrites(rewrites: list[PathRewrite]) -> int:
    if not rewrites:
        return 0

    now = int(time.time())
    changed = 0
    conn = open_db()
    try:
        for rewrite in rewrites:
            if rewrite.ref.table == "community_posts":
                cur = conn.execute(
                    "UPDATE community_posts SET image_path = ? WHERE id = ? AND image_path = ?",
                    (rewrite.canonical_raw_path, rewrite.ref.pk_value, rewrite.ref.raw_path),
                )
            elif rewrite.ref.table == "community_creative_jobs":
                cur = conn.execute(
                    f"""
                    UPDATE community_creative_jobs
                    SET {rewrite.ref.column} = ?, updated_at = ?
                    WHERE id = ? AND {rewrite.ref.column} = ?
                    """,
                    (rewrite.canonical_raw_path, now, rewrite.ref.pk_value, rewrite.ref.raw_path),
                )
            else:
                raise RuntimeError(f"unsupported table: {rewrite.ref.table}")
            changed += max(0, int(cur.rowcount))
        conn.commit()
    finally:
        conn.close()
    return changed


def _delete_file(path: Path) -> bool:
    if not path.exists():
        return True
    try:
        path.unlink()
    except Exception:
        return False

    for root in _mutable_roots():
        if not _is_under_root(path, root):
            continue
        parent = path.parent
        while parent != root and parent.exists():
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent
        break
    return True


def reconcile_local_images(apply_changes: bool = False) -> dict[str, Any]:
    refs_before = _load_db_refs()
    missing_refs = [ref for ref in refs_before if not ref.exists]
    rewrites = _plan_rewrites(refs_before)

    final_ref_path_by_key: dict[tuple[str, int, str], Path] = {}
    rewrite_map = {
        (rewrite.ref.table, rewrite.ref.pk_value, rewrite.ref.column): rewrite.canonical_resolved_path
        for rewrite in rewrites
    }
    for ref in refs_before:
        key = (ref.table, ref.pk_value, ref.column)
        final_ref_path_by_key[key] = rewrite_map.get(key, ref.resolved_path)

    referenced_mutable_paths = {
        str(path)
        for path in final_ref_path_by_key.values()
        if _is_mutable_path(path)
    }
    filesystem_files = _scan_filesystem_files()
    orphan_paths = [path for path in filesystem_files if str(path) not in referenced_mutable_paths]

    rewritten_rows = 0
    deleted_paths: list[str] = []
    failed_delete_paths: list[str] = []
    if apply_changes:
        rewritten_rows = _apply_rewrites(rewrites)
        for path in orphan_paths:
            if _delete_file(path):
                deleted_paths.append(str(path))
            else:
                failed_delete_paths.append(str(path))

    refs_after = _load_db_refs() if apply_changes else refs_before
    remaining_mutable_duplicates = _plan_rewrites(refs_after)
    remaining_fs_files = _scan_filesystem_files() if apply_changes else filesystem_files
    remaining_referenced_mutable = {
        str(ref.resolved_path)
        for ref in refs_after
        if ref.exists and _is_mutable_path(ref.resolved_path)
    }
    remaining_orphans = [str(path) for path in remaining_fs_files if str(path) not in remaining_referenced_mutable]

    return {
        "ok": len(failed_delete_paths) == 0,
        "apply_changes": apply_changes,
        "db_ref_count": len(refs_before),
        "missing_db_refs": len(missing_refs),
        "planned_db_rewrites": len(rewrites),
        "rewritten_rows": rewritten_rows,
        "orphan_file_count": len(orphan_paths),
        "deleted_file_count": len(deleted_paths),
        "failed_delete_count": len(failed_delete_paths),
        "remaining_mutable_duplicate_refs": len(remaining_mutable_duplicates),
        "remaining_orphan_files": len(remaining_orphans),
        "planned_rewrites": [
            {
                "table": rewrite.ref.table,
                "id": rewrite.ref.pk_value,
                "column": rewrite.ref.column,
                "from": rewrite.ref.raw_path,
                "to": rewrite.canonical_raw_path,
            }
            for rewrite in rewrites[:50]
        ],
        "orphan_samples": [str(path) for path in orphan_paths[:50]],
        "missing_ref_samples": [
            {
                "table": ref.table,
                "id": ref.pk_value,
                "column": ref.column,
                "path": ref.raw_path,
            }
            for ref in missing_refs[:50]
        ],
        "failed_delete_samples": failed_delete_paths[:50],
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Delete local image files that are no longer referenced by the database and dedupe duplicate mutable image refs."
    )
    parser.add_argument("--apply", action="store_true", help="Actually update DB refs and delete orphan files")
    args = parser.parse_args()

    result = reconcile_local_images(apply_changes=args.apply)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
