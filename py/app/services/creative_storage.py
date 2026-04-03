from __future__ import annotations

import base64
import hashlib
import mimetypes
from dataclasses import dataclass
from pathlib import Path

from app.core.config import PROJECT_ROOT
from app.core.config import SETTINGS

try:
    import boto3
    from botocore.config import Config as BotoConfig
except Exception:  # pragma: no cover - optional import until dependencies are installed
    boto3 = None  # type: ignore[assignment]
    BotoConfig = None  # type: ignore[assignment]


@dataclass(frozen=True)
class StoredObject:
    provider: str
    storage_key: str
    mime_type: str
    file_size: int
    sha256: str


class CreativeObjectStorage:
    def __init__(self) -> None:
        self._client = None

    def provider_name(self) -> str:
        provider = SETTINGS.creative_storage_provider.strip().lower()
        if provider in {"s3", "local"}:
            return provider
        return "local"

    def _s3_client(self):
        if boto3 is None or BotoConfig is None:
            raise RuntimeError("boto3 is not installed")
        if self._client is None:
            self._client = boto3.client(
                "s3",
                endpoint_url=SETTINGS.creative_storage_endpoint,
                aws_access_key_id=SETTINGS.creative_storage_access_key,
                aws_secret_access_key=SETTINGS.creative_storage_secret_key,
                region_name=SETTINGS.creative_storage_region,
                config=BotoConfig(signature_version="s3v4"),
            )
        return self._client

    def _decode_image(self, image_base64: str | None) -> tuple[bytes, str]:
        if not image_base64:
            return b"", "application/octet-stream"
        cleaned = image_base64.strip()
        if cleaned.startswith("data:image") and "," in cleaned:
            meta, cleaned = cleaned.split(",", 1)
            guessed = meta.split(";")[0].split(":", 1)[-1].strip()
        else:
            guessed = ""
        raw = base64.b64decode(cleaned, validate=True)
        if raw.startswith(b"\x89PNG"):
            return raw, "image/png"
        if guessed:
            return raw, guessed
        return raw, "image/jpeg"

    def store_image(self, job_id: int, label: str, image_base64: str | None) -> StoredObject | None:
        raw, mime_type = self._decode_image(image_base64)
        if not raw:
            return None
        sha256 = hashlib.sha256(raw).hexdigest()
        suffix = mimetypes.guess_extension(mime_type) or ".jpg"
        storage_key = (
            f"{SETTINGS.creative_storage_prefix.strip().strip('/')}/"
            f"jobs/{max(0, int(job_id)):06d}/{label}{suffix}"
        ).lstrip("/")

        provider = self.provider_name()
        if provider == "s3":
            client = self._s3_client()
            bucket = SETTINGS.creative_storage_bucket
            self._ensure_bucket(client, bucket)
            client.put_object(
                Bucket=bucket,
                Key=storage_key,
                Body=raw,
                ContentType=mime_type,
            )
        else:
            path = (SETTINGS.community_creative_result_dir / storage_key).resolve()
            base_dir = SETTINGS.community_creative_result_dir.resolve()
            if base_dir not in path.parents:
                raise RuntimeError("invalid local storage path")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)

        return StoredObject(
            provider=provider,
            storage_key=storage_key,
            mime_type=mime_type,
            file_size=len(raw),
            sha256=sha256,
        )

    def load_base64(self, provider: str, storage_key: str) -> str | None:
        safe_provider = (provider or "").strip().lower()
        safe_key = (storage_key or "").strip().lstrip("/")
        if not safe_provider or not safe_key:
            return None
        try:
            if safe_provider == "s3":
                client = self._s3_client()
                obj = client.get_object(Bucket=SETTINGS.creative_storage_bucket, Key=safe_key)
                raw = obj["Body"].read()
            else:
                path = (SETTINGS.community_creative_result_dir / safe_key).resolve()
                base_dir = SETTINGS.community_creative_result_dir.resolve()
                if base_dir not in path.parents or not path.exists():
                    return None
                raw = path.read_bytes()
        except Exception:
            return None
        if not raw:
            return None
        return base64.b64encode(raw).decode("utf-8")

    def delete_object(self, provider: str, storage_key: str) -> bool:
        safe_provider = (provider or "").strip().lower()
        safe_key = (storage_key or "").strip().lstrip("/")
        if not safe_provider or not safe_key:
            return True
        try:
            if safe_provider == "s3":
                client = self._s3_client()
                client.delete_object(Bucket=SETTINGS.creative_storage_bucket, Key=safe_key)
            else:
                path = (SETTINGS.community_creative_result_dir / safe_key).resolve()
                base_dir = SETTINGS.community_creative_result_dir.resolve()
                if base_dir in path.parents and path.exists():
                    path.unlink()
                    parent = path.parent
                    while parent != base_dir and parent.exists():
                        try:
                            parent.rmdir()
                        except Exception:
                            break
                        parent = parent.parent
        except Exception:
            return False
        return True

    def preflight(self) -> None:
        provider = self.provider_name()
        if provider == "s3":
            client = self._s3_client()
            self._ensure_bucket(client, SETTINGS.creative_storage_bucket)
            return
        base_dir = SETTINGS.community_creative_result_dir.resolve()
        base_dir.mkdir(parents=True, exist_ok=True)
        probe = base_dir / ".storage_probe"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink(missing_ok=True)

    def _ensure_bucket(self, client, bucket: str) -> None:
        try:
            client.head_bucket(Bucket=bucket)
            return
        except Exception:
            pass
        client.create_bucket(Bucket=bucket)


_OBJECT_STORAGE = CreativeObjectStorage()


def get_creative_object_storage() -> CreativeObjectStorage:
    return _OBJECT_STORAGE
