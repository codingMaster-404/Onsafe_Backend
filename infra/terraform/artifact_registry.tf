# Artifact Registry — Docker 이미지 저장소 (asia-northeast3)
resource "google_artifact_registry_repository" "onsafe" {
  location      = var.region
  repository_id = var.artifact_registry_repo
  description   = "OnSafe backend docker images (kotlin-api, python-ai)"
  format        = "DOCKER"

  depends_on = [google_project_service.enabled]
}

output "artifact_registry_repo_url" {
  description = "이미지 push/pull 경로 prefix"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.onsafe.repository_id}"
}