#!/usr/bin/env bash
# Secret Manager 시크릿 슬롯 생성 + 런타임 SA accessor 부여
# 실제 값은 별도로 add-version 명령으로 등록 (아래 예시 참고)
set -euo pipefail
source "$(dirname "$0")/00_env.sh"

SECRETS=(
  JWT_SECRET
  ENCRYPTION_AES_KEY
  AWS_ACCESS_KEY_ID
  AWS_SECRET_ACCESS_KEY
)

for name in "${SECRETS[@]}"; do
  if gcloud secrets describe "${name}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    echo ">> Secret '${name}' already exists — skipping create."
  else
    echo ">> Creating secret '${name}'..."
    gcloud secrets create "${name}" \
      --project="${PROJECT_ID}" \
      --replication-policy=automatic
  fi

  echo ">> Granting accessor to runtime SA on ${name}..."
  gcloud secrets add-iam-policy-binding "${name}" \
    --project="${PROJECT_ID}" \
    --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
    --role="roles/secretmanager.secretAccessor" --quiet >/dev/null
done

cat <<'EOF'

>> 값 등록 예시 (개별 실행 — 값은 절대 git에 커밋 금지):
   # openssl rand -base64 48 | tr -d '\n' | gcloud secrets versions add JWT_SECRET --data-file=-
   # openssl rand -base64 32 | tr -d '\n' | gcloud secrets versions add ENCRYPTION_AES_KEY --data-file=-
   # printf 'AKIA...' | gcloud secrets versions add AWS_ACCESS_KEY_ID --data-file=-
   # printf 'xxxxxx' | gcloud secrets versions add AWS_SECRET_ACCESS_KEY --data-file=-
EOF