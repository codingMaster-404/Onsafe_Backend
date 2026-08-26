#!/usr/bin/env bash
# Memorystore for Redis 프로비저닝
set -euo pipefail
source "$(dirname "$0")/00_env.sh"

if gcloud redis instances describe "${REDIS_INSTANCE}" \
    --region="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo ">> Redis '${REDIS_INSTANCE}' already exists — skipping create."
else
  echo ">> Creating Redis '${REDIS_INSTANCE}' (${REDIS_TIER}, ${REDIS_MEMORY_GB} GB)..."
  gcloud redis instances create "${REDIS_INSTANCE}" \
    --project="${PROJECT_ID}" \
    --region="${REGION}" \
    --tier="${REDIS_TIER}" \
    --size="${REDIS_MEMORY_GB}" \
    --redis-version=redis_7_2 \
    --network="projects/${PROJECT_ID}/global/networks/${VPC_NETWORK}" \
    --connect-mode=DIRECT_PEERING \
    --redis-config=maxmemory-policy=allkeys-lru
fi

HOST=$(gcloud redis instances describe "${REDIS_INSTANCE}" \
  --project="${PROJECT_ID}" --region="${REGION}" --format='value(host)')
PORT=$(gcloud redis instances describe "${REDIS_INSTANCE}" \
  --project="${PROJECT_ID}" --region="${REGION}" --format='value(port)')

cat <<EOF

>> Redis endpoint:
   REDIS_HOST = ${HOST}
   REDIS_PORT = ${PORT}

   Cloud Run 배포 시 --set-env-vars REDIS_URL=redis://${HOST}:${PORT} 형태로 주입.
EOF