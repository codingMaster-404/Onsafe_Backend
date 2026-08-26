# Cloud Run 워크로드가 사용할 서비스 계정 + IAM 권한
resource "google_service_account" "cloud_run" {
  account_id   = var.cloud_run_sa_id
  display_name = "OnSafe Cloud Run runtime SA"
  description  = "Cloud Run 컨테이너가 Firebase/GCS/FCM/Secret Manager에 접근할 때 사용"

  depends_on = [google_project_service.enabled]
}

# Firebase Admin SDK가 필요로 하는 기본 권한
resource "google_project_iam_member" "cloud_run_datastore_user" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_project_iam_member" "cloud_run_firebase_admin" {
  project = var.project_id
  role    = "roles/firebase.admin"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

# GCS Signed URL 서명(V4)에 필요한 자체 토큰 발급 권한
resource "google_service_account_iam_member" "cloud_run_self_token_creator" {
  service_account_id = google_service_account.cloud_run.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.cloud_run.email}"
}

# FCM 발송을 위한 권한
resource "google_project_iam_member" "cloud_run_fcm_admin" {
  project = var.project_id
  role    = "roles/firebasenotifications.admin"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

# GCS 버킷 오브젝트 관리 (특정 버킷 한정으로 좁히는 것을 권장, 여기서는 프로젝트 전체)
resource "google_project_iam_member" "cloud_run_storage_admin" {
  project = var.project_id
  role    = "roles/storage.objectAdmin"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

# 로그/모니터링 기본 권한
resource "google_project_iam_member" "cloud_run_log_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_project_iam_member" "cloud_run_metric_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.cloud_run.email}"
}

output "cloud_run_service_account_email" {
  value = google_service_account.cloud_run.email
}