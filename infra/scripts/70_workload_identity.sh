#!/usr/bin/env bash
# GitHub Actions ↔ GCP Workload Identity Federation 설정
set -euo pipefail
source "$(dirname "$0")/00_env.sh"

PROJECT_NUMBER=$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')

# ── Pool ─────────────────────────────────────────────────────────────────
if gcloud iam workload-identity-pools describe "${WIF_POOL_ID}" \
    --project="${PROJECT_ID}" --location=global >/dev/null 2>&1; then
  echo ">> WIF Pool '${WIF_POOL_ID}' already exists — skipping."
else
  echo ">> Creating WIF Pool '${WIF_POOL_ID}'..."
  gcloud iam workload-identity-pools create "${WIF_POOL_ID}" \
    --project="${PROJECT_ID}" \
    --location=global \
    --display-name="GitHub Actions Pool"
fi

# ── Provider (OIDC) ──────────────────────────────────────────────────────
if gcloud iam workload-identity-pools providers describe "${WIF_PROVIDER_ID}" \
    --project="${PROJECT_ID}" --location=global --workload-identity-pool="${WIF_POOL_ID}" >/dev/null 2>&1; then
  echo ">> WIF Provider '${WIF_PROVIDER_ID}' already exists — skipping."
else
  echo ">> Creating WIF Provider '${WIF_PROVIDER_ID}'..."
  gcloud iam workload-identity-pools providers create-oidc "${WIF_PROVIDER_ID}" \
    --project="${PROJECT_ID}" \
    --location=global \
    --workload-identity-pool="${WIF_POOL_ID}" \
    --display-name="GitHub OIDC Provider" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
    --attribute-condition="assertion.repository == \"${GITHUB_REPO}\""
fi

# ── Deployer SA <-> WIF principalSet 바인딩 ─────────────────────────────
echo ">> Binding WIF principalSet to deployer SA..."
gcloud iam service-accounts add-iam-policy-binding "${DEPLOYER_SA_EMAIL}" \
  --project="${PROJECT_ID}" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL_ID}/attribute.repository/${GITHUB_REPO}" \
  --quiet >/dev/null

WIF_PROVIDER_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL_ID}/providers/${WIF_PROVIDER_ID}"

cat <<EOF

>> GitHub Actions에 등록할 값 (Repository → Settings → Secrets and variables → Actions → Variables):
   GCP_WORKLOAD_IDENTITY_PROVIDER = ${WIF_PROVIDER_RESOURCE}
   GCP_DEPLOYER_SERVICE_ACCOUNT   = ${DEPLOYER_SA_EMAIL}
   GCP_PROJECT_ID                 = ${PROJECT_ID}
   GCP_REGION                     = ${REGION}
   GCP_AR_REPO                    = ${AR_REPO}
   GCP_RUNTIME_SA                 = ${RUNTIME_SA_EMAIL}
EOF