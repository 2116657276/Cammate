from __future__ import annotations

import argparse
import json
import time
import uuid
from urllib.parse import urlparse

import boto3
from botocore.config import Config as BotoConfig
from redis import Redis


def _bad_redis_url(url: str) -> str:
    parsed = urlparse(url)
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or 6379
    bad_port = port + 17
    db_path = parsed.path or "/0"
    return f"redis://{host}:{bad_port}{db_path}"


def check_redis_normal(redis_url: str) -> dict[str, object]:
    client = Redis.from_url(redis_url, decode_responses=True, socket_connect_timeout=1.5, socket_timeout=1.5)
    key = f"cammate:probe:{uuid.uuid4().hex[:10]}"
    value = uuid.uuid4().hex
    start = time.time()
    pong = bool(client.ping())
    client.set(key, value, ex=20)
    read_back = client.get(key)
    client.delete(key)
    return {
        "ok": pong and read_back == value,
        "latency_ms": int((time.time() - start) * 1000),
        "read_back_match": read_back == value,
    }


def check_redis_exception(redis_url: str) -> dict[str, object]:
    bad_url = _bad_redis_url(redis_url)
    try:
        client = Redis.from_url(bad_url, decode_responses=True, socket_connect_timeout=0.8, socket_timeout=0.8)
        client.ping()
    except Exception as exc:
        return {"ok": True, "expected_failure": True, "reason": str(exc)[:160], "url": bad_url}
    return {"ok": False, "expected_failure": False, "reason": "unexpectedly connected", "url": bad_url}


def _s3_client(endpoint: str, access_key: str, secret_key: str, region: str):
    return boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name=region,
        config=BotoConfig(signature_version="s3v4"),
    )


def _ensure_bucket(client, bucket: str) -> None:
    try:
        client.head_bucket(Bucket=bucket)
        return
    except Exception:
        pass
    client.create_bucket(Bucket=bucket)


def check_minio_normal(endpoint: str, access_key: str, secret_key: str, region: str, bucket: str) -> dict[str, object]:
    client = _s3_client(endpoint, access_key, secret_key, region)
    _ensure_bucket(client, bucket)
    key = f"probe/{uuid.uuid4().hex}.txt"
    payload = f"probe-{uuid.uuid4().hex}".encode("utf-8")
    start = time.time()
    client.put_object(Bucket=bucket, Key=key, Body=payload, ContentType="text/plain")
    got = client.get_object(Bucket=bucket, Key=key)["Body"].read()
    client.delete_object(Bucket=bucket, Key=key)
    return {
        "ok": got == payload,
        "latency_ms": int((time.time() - start) * 1000),
        "read_back_match": got == payload,
        "bucket": bucket,
    }


def check_minio_exception(endpoint: str, access_key: str, secret_key: str, region: str, bucket: str) -> dict[str, object]:
    wrong_secret = f"{secret_key}_wrong"
    client = _s3_client(endpoint, access_key, wrong_secret, region)
    key = f"probe/bad-{uuid.uuid4().hex}.txt"
    try:
        client.put_object(Bucket=bucket, Key=key, Body=b"bad", ContentType="text/plain")
    except Exception as exc:
        return {"ok": True, "expected_failure": True, "reason": str(exc)[:160]}
    return {"ok": False, "expected_failure": False, "reason": "unexpectedly succeeded"}


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Redis + MinIO chain in normal and failure scenarios")
    parser.add_argument("--redis-url", required=True)
    parser.add_argument("--storage-endpoint", default="")
    parser.add_argument("--storage-access-key", default="")
    parser.add_argument("--storage-secret-key", default="")
    parser.add_argument("--storage-region", default="us-east-1")
    parser.add_argument("--storage-bucket", default="")
    parser.add_argument("--skip-minio", action="store_true")
    args = parser.parse_args()

    results: dict[str, dict[str, object]] = {
        "redis_normal": check_redis_normal(args.redis_url),
        "redis_exception": check_redis_exception(args.redis_url),
    }

    if args.skip_minio:
        # 在纯本机联调（未启动对象存储服务）时可仅验证 Redis。
        results["minio_normal"] = {"ok": True, "skipped": True, "reason": "skip_minio_enabled"}
        results["minio_exception"] = {"ok": True, "skipped": True, "reason": "skip_minio_enabled"}
    else:
        missing = []
        if not args.storage_endpoint:
            missing.append("storage-endpoint")
        if not args.storage_access_key:
            missing.append("storage-access-key")
        if not args.storage_secret_key:
            missing.append("storage-secret-key")
        if not args.storage_bucket:
            missing.append("storage-bucket")
        if missing:
            raise SystemExit(f"missing required args for MinIO mode: {', '.join(missing)}")
        results["minio_normal"] = check_minio_normal(
            endpoint=args.storage_endpoint,
            access_key=args.storage_access_key,
            secret_key=args.storage_secret_key,
            region=args.storage_region,
            bucket=args.storage_bucket,
        )
        results["minio_exception"] = check_minio_exception(
            endpoint=args.storage_endpoint,
            access_key=args.storage_access_key,
            secret_key=args.storage_secret_key,
            region=args.storage_region,
            bucket=args.storage_bucket,
        )

    ok = all(bool(item.get("ok")) for item in results.values())
    payload = {"ok": ok, "results": results}
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
