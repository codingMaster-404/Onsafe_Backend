#!/usr/bin/env bash
# 10.3 GCP 인프라 일괄 프로비저닝 (순차 실행)
# 실행 전 반드시 `source infra/scripts/00_env.sh` 로 값 확인
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

bash "${DIR}/10_enable_apis.sh"
bash "${DIR}/20_artifact_registry.sh"
bash "${DIR}/30_service_accounts.sh"
bash "${DIR}/40_secrets.sh"
bash "${DIR}/50_vpc_connector.sh"
bash "${DIR}/60_redis.sh"
bash "${DIR}/70_workload_identity.sh"

echo
echo ">> ALL DONE. Secret Manager 4개 시크릿의 실제 값은 아직 비어 있으므로 별도 등록 필요."