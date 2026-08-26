# Secret Manager — 4개 시크릿 슬롯 정의
# 실제 값은 별도로 등록해야 함 (git에 값 커밋 금지)
#   gcloud secrets versions add JWT_SECRET --data-file=- <<< "..."
locals {
  secret_ids = [
    "JWT_SECRET",
    "ENCRYPTION_AES_KEY",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
  ]
}

resource "google_secret_manager_secret" "secrets" {
  for_each  = toset(local.secret_ids)
  secret_id = each.key

  replication {
    auto {}
  }

  depends_on = [google_project_service.enabled]
}

# Cloud Run 서비스 계정에 각 시크릿의 접근 권한 부여
resource "google_secret_manager_secret_iam_member" "cloud_run_access" {
  for_each  = google_secret_manager_secret.secrets
  secret_id = each.value.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}