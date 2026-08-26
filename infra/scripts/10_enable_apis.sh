#!/usr/bin/env bash
# 배포에 필요한 GCP API 일괄 활성화
set -euo pipefail
source "$(dirname "$0")/00_env.sh"

gcloud config set project "${PROJECT_ID}" >/dev/null

APIS=(
  run.googleapis.com
  artifactregistry.googleapis.com
  secretmanager.googleapis.com
  redis.googleapis.com
  vpcaccess.googleapis.com
  iam.googleapis.com
  iamcredentials.googleapis.com
  sts.googleapis.com
  cloudresourcemanager.googleapis.com
  compute.googleapis.com
  firestore.googleapis.com
  firebase.googleapis.com
  storage.googleapis.com
  fcm.googleapis.com
  logging.googleapis.com
  monitoring.googleapis.com
)

echo ">> Enabling ${#APIS[@]} APIs..."
gcloud services enable "${APIS[@]}"
echo ">> Done."