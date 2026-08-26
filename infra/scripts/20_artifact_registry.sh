#!/usr/bin/env bash
# Artifact Registry Docker 저장소 생성 (idempotent)
set -euo pipefail
source "$(dirname "$0")/00_env.sh"

if gcloud artifacts repositories describe "${AR_REPO}" \
    --location="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo ">> Artifact Registry '${AR_REPO}' already exists in ${REGION} — skipping."
else
  echo ">> Creating Artifact Registry '${AR_REPO}' in ${REGION}..."
  gcloud artifacts repositories create "${AR_REPO}" \
    --repository-format=docker \
    --location="${REGION}" \
    --description="OnSafe backend docker images"
fi

echo ">> Push URL prefix: ${AR_REPO_URL}"
echo ">> Auth 예시:  gcloud auth configure-docker ${REGION}-docker.pkg.dev"