#!/usr/bin/env bash
# Cloud Run 런타임 SA + GitHub Actions 배포 SA 생성 및 IAM 부여
set -euo pipefail
source "$(dirname "$0")/00_env.sh"

# ── 런타임 SA ─────────────────────────────────────────────────────────────
if gcloud iam service-accounts describe "${RUNTIME_SA_EMAIL}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo ">> Runtime SA already exists — skipping create."
else
  echo ">> Creating runtime SA ${RUNTIME_SA_EMAIL}..."
  gcloud iam service-accounts create "${RUNTIME_SA_ID}" \
    --project="${PROJECT_ID}" \
    --display-name="OnSafe Cloud Run runtime SA"
fi

RUNTIME_ROLES=(
  roles/datastore.user
  roles/firebase.admin
  roles/firebasenotifications.admin
  roles/storage.objectAdmin
  roles/logging.logWriter
  roles/monitoring.metricWriter
)

for role in "${RUNTIME_ROLES[@]}"; do
  echo ">> Binding ${role} to runtime SA..."
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
    --role="${role}" --condition=None --quiet >/dev/null
done

# 자기 자신에 대한 tokenCreator (GCS Signed URL V4 서명용)
echo ">> Binding self tokenCreator to runtime SA..."
gcloud iam service-accounts add-iam-policy-binding "${RUNTIME_SA_EMAIL}" \
  --project="${PROJECT_ID}" \
  --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
  --role="roles/iam.serviceAccountTokenCreator" --quiet >/dev/null

# ── 배포용 SA ─────────────────────────────────────────────────────────────
if gcloud iam service-accounts describe "${DEPLOYER_SA_EMAIL}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo ">> Deployer SA already exists — skipping create."
else
  echo ">> Creating deployer SA ${DEPLOYER_SA_EMAIL}..."
  gcloud iam service-accounts create "${DEPLOYER_SA_ID}" \
    --project="${PROJECT_ID}" \
    --display-name="OnSafe GitHub Actions Deployer"
fi

DEPLOYER_ROLES=(
  roles/artifactregistry.writer
  roles/run.admin
)

for role in "${DEPLOYER_ROLES[@]}"; do
  echo ">> Binding ${role} to deployer SA..."
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${DEPLOYER_SA_EMAIL}" \
    --role="${role}" --condition=None --quiet >/dev/null
done

# 배포 SA가 런타임 SA를 impersonate 할 수 있도록 actAs 부여
echo ">> Granting deployer actAs runtime SA..."
gcloud iam service-accounts add-iam-policy-binding "${RUNTIME_SA_EMAIL}" \
  --project="${PROJECT_ID}" \
  --member="serviceAccount:${DEPLOYER_SA_EMAIL}" \
  --role="roles/iam.serviceAccountUser" --quiet >/dev/null

echo ">> Done."