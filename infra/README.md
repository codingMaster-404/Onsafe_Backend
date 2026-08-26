# OnSafe Backend — GCP 인프라 (10.3)

배포 준비 체크리스트 **10.3 GCP 인프라 준비** 항목을 자동화한 것.
Terraform과 gcloud CLI 스크립트 두 가지 방식 모두 제공하며, 어느 쪽을 써도 결과는 동일하다.

## 프로비저닝 대상

| 항목 | 리소스 |
| --- | --- |
| Artifact Registry | Docker 저장소 (asia-northeast3, `onsafe`) |
| Secret Manager | `JWT_SECRET`, `ENCRYPTION_AES_KEY`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` |
| Cloud Run 서비스 계정 | 런타임용 `onsafe-cloudrun` + IAM (Firestore/Firebase/Storage/FCM/로그) |
| Memorystore for Redis | 1GB BASIC (`onsafe-redis`) — VPC 내부 접근만 허용 |
| Serverless VPC Access Connector | `onsafe-connector` (10.8.0.0/28) |
| Workload Identity Federation | GitHub Actions (`codingMaster-404/Onsafe_Backend`) OIDC |
| 배포용 서비스 계정 | `onsafe-deployer` (Artifact Registry writer + Cloud Run admin) |

Firebase Admin SDK 초기화는 이미 코드에서 ADC 우선으로 전환됨
(`src/main/kotlin/.../FirebaseConfig.kt`, `app/core/firebase.py`).
Cloud Run에 runtime SA를 붙이면 자격증명 파일 없이 Firestore/Storage/FCM 사용 가능.

---

## 사전 준비

```bash
# gcloud CLI 로그인 & ADC 로그인
gcloud auth login
gcloud auth application-default login

# 대상 프로젝트 지정
gcloud config set project on-safe-f1667

# (선택) Terraform 설치
brew install terraform
```

## 방법 A. gcloud 스크립트 (즉시 실행)

값을 자기 환경에 맞게 수정한 뒤:

```bash
# 1) 환경변수 확인
source infra/scripts/00_env.sh

# 2) 일괄 프로비저닝
bash infra/scripts/99_provision_all.sh
```

개별 스텝만 실행하고 싶다면:

```bash
bash infra/scripts/10_enable_apis.sh
bash infra/scripts/20_artifact_registry.sh
bash infra/scripts/30_service_accounts.sh
bash infra/scripts/40_secrets.sh          # 슬롯만 생성, 실제 값은 별도 등록
bash infra/scripts/50_vpc_connector.sh
bash infra/scripts/60_redis.sh
bash infra/scripts/70_workload_identity.sh
```

### Secret 실제 값 등록

```bash
# 32자 이상 랜덤 (JWT 서명 키)
openssl rand -base64 48 | tr -d '\n' \
  | gcloud secrets versions add JWT_SECRET --data-file=-

# base64 32바이트 (AES-256-GCM 키)
openssl rand -base64 32 | tr -d '\n' \
  | gcloud secrets versions add ENCRYPTION_AES_KEY --data-file=-

# SES IAM 사용자 자격증명
printf 'AKIAxxxxxxxx' | gcloud secrets versions add AWS_ACCESS_KEY_ID --data-file=-
printf 'xxxxxxxxxxxx' | gcloud secrets versions add AWS_SECRET_ACCESS_KEY --data-file=-
```

## 방법 B. Terraform

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# terraform.tfvars 편집 (project_id, github_repository, gcs_bucket_name 등)

terraform init
terraform plan
terraform apply
```

출력값 예:
```
artifact_registry_repo_url      = "asia-northeast3-docker.pkg.dev/on-safe-f1667/onsafe"
cloud_run_service_account_email = "onsafe-cloudrun@on-safe-f1667.iam.gserviceaccount.com"
deployer_service_account_email  = "onsafe-deployer@on-safe-f1667.iam.gserviceaccount.com"
redis_host                      = "10.x.x.x"
redis_port                      = 6379
vpc_connector_id                = "projects/on-safe-f1667/locations/asia-northeast3/connectors/onsafe-connector"
wif_provider_resource_name      = "projects/<num>/locations/global/workloadIdentityPools/github-actions/providers/github"
```

Secret 실제 값 등록은 위 gcloud 예시와 동일 (`terraform apply`가 값까지 넣지는 않음).

---

## GitHub Actions에 등록할 값

Repo → Settings → Secrets and variables → **Actions → Variables**:

| 이름 | 값 |
| --- | --- |
| `GCP_PROJECT_ID` | `on-safe-f1667` |
| `GCP_REGION` | `asia-northeast3` |
| `GCP_AR_REPO` | `onsafe` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | 위 output의 `wif_provider_resource_name` |
| `GCP_DEPLOYER_SERVICE_ACCOUNT` | `onsafe-deployer@<project>.iam.gserviceaccount.com` |
| `GCP_RUNTIME_SA` | `onsafe-cloudrun@<project>.iam.gserviceaccount.com` |

CI/CD 워크플로 자체(체크리스트 10.5)는 별도 회의 후 확정 예정이므로 이 저장소에서는 아직 gcloud 배포 스텝을 추가하지 않았음.

---

## Cloud Run 배포 시 참고

런타임 SA와 시크릿 주입 예시 (10.5 회의 후 GitHub Actions에 반영):

```bash
gcloud run deploy onsafe-kotlin-api \
  --image asia-northeast3-docker.pkg.dev/on-safe-f1667/onsafe/onsafe-kotlin-api:latest \
  --region asia-northeast3 \
  --service-account onsafe-cloudrun@on-safe-f1667.iam.gserviceaccount.com \
  --vpc-connector onsafe-connector \
  --vpc-egress private-ranges-only \
  --set-env-vars SPRING_PROFILES_ACTIVE=prod,AWS_REGION=ap-northeast-2,TZ=Asia/Seoul,REDIS_HOST=10.x.x.x,REDIS_PORT=6379,FIREBASE_STORAGE_BUCKET=on-safe-f1667.appspot.com \
  --set-secrets JWT_SECRET=JWT_SECRET:latest,ENCRYPTION_AES_KEY=ENCRYPTION_AES_KEY:latest,AWS_ACCESS_KEY_ID=AWS_ACCESS_KEY_ID:latest,AWS_SECRET_ACCESS_KEY=AWS_SECRET_ACCESS_KEY:latest
```

Python AI 서버는 `--ingress internal` + `KOTLIN_INTERNAL_BASE` 를 Kotlin 서비스 URL로 지정.

---

## 정리 (Destroy)

```bash
# Terraform 사용 시
cd infra/terraform && terraform destroy

# gcloud 사용 시 개별 삭제 (Redis만 몇 분 소요, VPC connector도 몇 분 소요)
gcloud redis instances delete "$REDIS_INSTANCE" --region="$REGION"
gcloud compute networks vpc-access connectors delete "$VPC_CONNECTOR" --region="$REGION"
gcloud artifacts repositories delete "$AR_REPO" --location="$REGION"
```