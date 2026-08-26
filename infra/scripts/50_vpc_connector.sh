#!/usr/bin/env bash
# Serverless VPC Access Connector — Cloud Run에서 Memorystore 프라이빗 IP 접근용
set -euo pipefail
source "$(dirname "$0")/00_env.sh"

if gcloud compute networks vpc-access connectors describe "${VPC_CONNECTOR}" \
    --region="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo ">> VPC Connector '${VPC_CONNECTOR}' already exists — skipping."
else
  echo ">> Creating VPC Connector '${VPC_CONNECTOR}' (${VPC_CONNECTOR_CIDR}) in ${REGION}..."
  gcloud compute networks vpc-access connectors create "${VPC_CONNECTOR}" \
    --project="${PROJECT_ID}" \
    --region="${REGION}" \
    --network="${VPC_NETWORK}" \
    --range="${VPC_CONNECTOR_CIDR}" \
    --min-throughput=200 \
    --max-throughput=300
fi