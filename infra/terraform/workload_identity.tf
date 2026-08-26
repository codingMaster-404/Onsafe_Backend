# Workload Identity Federation — GitHub Actions에서 서비스 계정 키 파일 없이 GCP 배포
resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = var.wif_pool_id
  display_name              = "GitHub Actions Pool"
  description               = "Pool for GitHub Actions OIDC"

  depends_on = [google_project_service.enabled]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = var.wif_provider_id
  display_name                       = "GitHub OIDC Provider"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.actor"      = "assertion.actor"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  # 특정 리포지토리에서 온 토큰만 허용 (보안 필수)
  attribute_condition = "assertion.repository == \"${var.github_repository}\""

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# 배포용 별도 서비스 계정 (Cloud Run 런타임 SA와 분리)
resource "google_service_account" "deployer" {
  account_id   = "onsafe-deployer"
  display_name = "OnSafe GitHub Actions Deployer"
  description  = "GitHub Actions가 Artifact Registry push + Cloud Run deploy 수행"

  depends_on = [google_project_service.enabled]
}

# 배포 SA에 필요한 권한
resource "google_project_iam_member" "deployer_artifact_writer" {
  project = var.project_id
  role    = "roles/artifactregistry.writer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_member" "deployer_run_admin" {
  project = var.project_id
  role    = "roles/run.admin"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

# Cloud Run 서비스 배포 시 런타임 SA를 지정하려면 actAs 권한이 필요
resource "google_service_account_iam_member" "deployer_act_as_runtime" {
  service_account_id = google_service_account.cloud_run.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

# GitHub Actions 워크로드가 deployer SA를 impersonate 할 수 있도록 바인딩
resource "google_service_account_iam_member" "wif_deployer_binding" {
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

output "wif_provider_resource_name" {
  description = "GitHub Actions workflow의 workload_identity_provider 필드 값"
  value       = "projects/${data.google_project.current.number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github.workload_identity_pool_id}/providers/${google_iam_workload_identity_pool_provider.github.workload_identity_pool_provider_id}"
}

output "deployer_service_account_email" {
  value = google_service_account.deployer.email
}

data "google_project" "current" {
  project_id = var.project_id
}