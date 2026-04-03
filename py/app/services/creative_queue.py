from __future__ import annotations

import time
from dataclasses import dataclass

from app.core.config import SETTINGS

try:
    from redis import Redis
except Exception:  # pragma: no cover - optional import until dependencies are installed
    Redis = None  # type: ignore[assignment]


@dataclass(frozen=True)
class CreativeQueueMetrics:
    backend: str
    connected: bool
    ready: int
    delayed: int
    leases_expiring: int
    workers_active: int


class RedisCreativeQueue:
    def __init__(self) -> None:
        self._client = None
        self._ready_key = SETTINGS.creative_redis_ready_key
        self._delayed_key = SETTINGS.creative_redis_delayed_key
        self._score_key = f"{self._ready_key}:scores"
        self._lease_prefix = SETTINGS.creative_redis_lease_prefix
        self._worker_prefix = SETTINGS.creative_redis_worker_prefix

    def backend_name(self) -> str:
        return "redis"

    def is_enabled(self) -> bool:
        return SETTINGS.creative_queue_backend == "redis"

    def _redis(self):
        if Redis is None:
            raise RuntimeError("redis package is not installed")
        if self._client is None:
            self._client = Redis.from_url(
                SETTINGS.creative_redis_url,
                decode_responses=True,
                socket_connect_timeout=1.5,
                socket_timeout=2.5,
            )
        return self._client

    def ping(self) -> bool:
        if not self.is_enabled():
            return False
        try:
            return bool(self._redis().ping())
        except Exception:
            return False

    def enqueue_ready(self, job_id: int, ready_score: float) -> None:
        redis = self._redis()
        member = str(max(0, int(job_id)))
        pipe = redis.pipeline()
        pipe.zadd(self._ready_key, {member: float(ready_score)})
        pipe.hset(self._score_key, member, float(ready_score))
        pipe.zrem(self._delayed_key, member)
        pipe.execute()

    def enqueue_delayed(self, job_id: int, ready_score: float, execute_at: int) -> None:
        redis = self._redis()
        member = str(max(0, int(job_id)))
        pipe = redis.pipeline()
        pipe.zadd(self._delayed_key, {member: float(max(0, int(execute_at)))})
        pipe.hset(self._score_key, member, float(ready_score))
        pipe.zrem(self._ready_key, member)
        pipe.execute()

    def promote_due(self, now: int | None = None, limit: int = 64) -> int:
        redis = self._redis()
        safe_now = int(time.time()) if now is None else int(now)
        due = redis.zrangebyscore(self._delayed_key, min="-inf", max=safe_now, start=0, num=max(1, int(limit)))
        if not due:
            return 0
        moved = 0
        for member in due:
            pipe = redis.pipeline()
            pipe.zrem(self._delayed_key, member)
            pipe.hget(self._score_key, member)
            removed, stored_score = pipe.execute()
            if int(removed or 0) <= 0:
                continue
            score = float(stored_score) if stored_score is not None else float(safe_now)
            redis.zadd(self._ready_key, {member: score})
            moved += 1
        return moved

    def pop_ready(self, timeout_sec: float = 1.0) -> int | None:
        redis = self._redis()
        self.promote_due()
        result = redis.bzpopmin(self._ready_key, timeout=max(1, int(round(timeout_sec))))
        if not result:
            return None
        _, member, _ = result
        try:
            return int(member)
        except Exception:
            return None

    def set_lease(self, job_id: int, ttl_sec: int, worker_name: str) -> None:
        redis = self._redis()
        key = f"{self._lease_prefix}{max(0, int(job_id))}"
        redis.set(key, worker_name[:80], ex=max(1, int(ttl_sec)))

    def clear_lease(self, job_id: int) -> None:
        redis = self._redis()
        redis.delete(f"{self._lease_prefix}{max(0, int(job_id))}")

    def touch_worker(self, worker_name: str, ttl_sec: int) -> None:
        redis = self._redis()
        safe_name = (worker_name or "worker").strip()[:64] or "worker"
        key = f"{self._worker_prefix}{safe_name}"
        redis.set(key, str(int(time.time())), ex=max(1, int(ttl_sec)))

    def metrics(self, warn_before_sec: int = 6) -> CreativeQueueMetrics:
        if not self.ping():
            return CreativeQueueMetrics(
                backend=self.backend_name(),
                connected=False,
                ready=0,
                delayed=0,
                leases_expiring=0,
                workers_active=0,
            )

        redis = self._redis()
        ready = int(redis.zcard(self._ready_key))
        delayed = int(redis.zcard(self._delayed_key))
        expiring = 0
        for key in redis.scan_iter(match=f"{self._lease_prefix}*"):
            try:
                ttl = int(redis.ttl(key))
            except Exception:
                continue
            if 0 <= ttl <= max(1, int(warn_before_sec)):
                expiring += 1
        workers_active = 0
        for _ in redis.scan_iter(match=f"{self._worker_prefix}*"):
            workers_active += 1
        return CreativeQueueMetrics(
            backend=self.backend_name(),
            connected=True,
            ready=max(0, ready),
            delayed=max(0, delayed),
            leases_expiring=max(0, expiring),
            workers_active=max(0, workers_active),
        )


_REDIS_QUEUE = RedisCreativeQueue()


def get_redis_creative_queue() -> RedisCreativeQueue:
    return _REDIS_QUEUE
