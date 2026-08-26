#!/usr/bin/env bash
# 모든 스크립트가 공유하는 환경변수. 값을 자신의 환경에 맞게 수정 후 source.
#   source infra/scripts/00_env.sh
set -euo pipefail

# ── 프로젝트 기본 ────────────────────────────────────────────────────────────
export PROJECT_ID="${PROJECT_ID:-on-safe-f1667}"
export REGION="${REGION:-asia-northeast3}"

# ── 리소스 이름 ─────────────────────────────────────────────────────────────
export AR_REPO="${AR_REPO:-onsafe}"
export RUNTIME_SA_ID="${RUNTIME_SA_ID:-onsafe-cloudrun}"
export DEPLOYER_SA_ID="${DEPLOYER_SA_ID:-onsafe-deployer}"
export REDIS_INSTANCE="${REDIS_INSTANCE:-onsafe-redis}"
export REDIS_TIER="${REDIS_TIER:-BASIC}"
export REDIS_MEMORY_GB="${REDIS_MEMORY_GB:-1}"
export VPC_NETWORK="${VPC_NETWORK:-default}"
export VPC_CONNECTOR="${VPC_CONNECTOR:-onsafe-connector}"
export VPC_CONNECTOR_CIDR="${VPC_CONNECTOR_CIDR:-10.8.0.0/28}"

# ── Workload Identity Federation ────────────────────────────────────────────
export WIF_POOL_ID="${WIF_POOL_ID:-github-actions}"
export WIF_PROVIDER_ID="${WIF_PROVIDER_ID:-github}"
export GITHUB_REPO="${GITHUB_REPO:-codingMaster-404/Onsafe_Backend}"

# 파생값
export RUNTIME_SA_EMAIL="${RUNTIME_SA_ID}@${PROJECT_ID}.iam.gserviceaccount.com"
export DEPLOYER_SA_EMAIL="${DEPLOYER_SA_ID}@${PROJECT_ID}.iam.gserviceaccount.com"
export AR_REPO_URL="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}"

echo "PROJECT_ID          = ${PROJECT_ID}"
echo "REGION              = ${REGION}"
echo "AR_REPO_URL         = ${AR_REPO_URL}"
echo "RUNTIME_SA_EMAIL    = ${RUNTIME_SA_EMAIL}"
echo "DEPLOYER_SA_EMAIL   = ${DEPLOYER_SA_EMAIL}"
echo "REDIS_INSTANCE      = ${REDIS_INSTANCE} (${REDIS_TIER}, ${REDIS_MEMORY_GB} GB)"
echo "VPC_CONNECTOR       = ${VPC_CONNECTOR} (${VPC_CONNECTOR_CIDR})"
echo "GITHUB_REPO         = ${GITHUB_REPO}"