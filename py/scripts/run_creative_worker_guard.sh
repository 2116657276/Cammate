#!/usr/bin/env bash
set -euo pipefail

RESTART_DELAY_SEC="${RESTART_DELAY_SEC:-2}"
MAX_RESTART_DELAY_SEC="${MAX_RESTART_DELAY_SEC:-20}"
WORKER_CMD="${WORKER_CMD:-PYTHONPATH=py python py/scripts/run_creative_worker.py}"

attempt=0
delay="${RESTART_DELAY_SEC}"

while true; do
  attempt=$((attempt + 1))
  echo "[worker-guard] attempt=${attempt} cmd=${WORKER_CMD}"
  set +e
  eval "${WORKER_CMD}"
  code=$?
  set -e

  if [[ "${code}" -eq 0 ]]; then
    echo "[worker-guard] worker exited normally, stop guard"
    exit 0
  fi

  echo "[worker-guard] worker crashed code=${code}, restart in ${delay}s"
  sleep "${delay}"
  next=$((delay * 2))
  if [[ "${next}" -gt "${MAX_RESTART_DELAY_SEC}" ]]; then
    delay="${MAX_RESTART_DELAY_SEC}"
  else
    delay="${next}"
  fi
done
