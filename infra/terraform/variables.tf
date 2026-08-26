variable "project_id" {
  description = "GCP 프로덕션 프로젝트 ID (예: on-safe-f1667 or 신규 prod 프로젝트)"
  type        = string
}

variable "region" {
  description = "기본 리전"
  type        = string
  default     = "asia-northeast3"
}

variable "artifact_registry_repo" {
  description = "Artifact Registry 저장소 이름"
  type        = string
  default     = "onsafe"
}

variable "cloud_run_sa_id" {
  description = "Cloud Run 워크로드용 서비스 계정 ID (이메일 앞부분)"
  type        = string
  default     = "onsafe-cloudrun"
}

variable "redis_instance_id" {
  description = "Memorystore for Redis 인스턴스 ID"
  type        = string
  default     = "onsafe-redis"
}

variable "redis_memory_size_gb" {
  description = "Memorystore Redis 메모리 크기(GB)"
  type        = number
  default     = 1
}

variable "redis_tier" {
  description = "Memorystore Redis 티어 (BASIC or STANDARD_HA)"
  type        = string
  default     = "BASIC"
}

variable "vpc_network" {
  description = "Memorystore/VPC Connector가 사용할 VPC 네트워크 이름"
  type        = string
  default     = "default"
}

variable "vpc_connector_name" {
  description = "Serverless VPC Access Connector 이름"
  type        = string
  default     = "onsafe-connector"
}

variable "vpc_connector_ip_cidr" {
  description = "VPC Connector용 /28 CIDR (해당 리전 서브넷과 겹치지 않아야 함)"
  type        = string
  default     = "10.8.0.0/28"
}

variable "github_repository" {
  description = "GitHub 리포지토리 (owner/repo). Workload Identity Federation 대상"
  type        = string
  default     = "codingMaster-404/Onsafe_Backend"
}

variable "wif_pool_id" {
  description = "Workload Identity Pool ID"
  type        = string
  default     = "github-actions"
}

variable "wif_provider_id" {
  description = "Workload Identity Provider ID"
  type        = string
  default     = "github"
}

variable "gcs_bucket_name" {
  description = "낙상 영상 GCS 버킷 이름 (기존 Firebase Storage 버킷 재사용시 그대로)"
  type        = string
}

variable "firestore_project_id" {
  description = "Firestore가 있는 프로젝트 ID (보통 Firebase 프로젝트 = 이 프로젝트)"
  type        = string
  default     = ""
}