# Memorystore for Redis — VPC 내부 프라이빗 IP로만 접근
resource "google_redis_instance" "onsafe" {
  name               = var.redis_instance_id
  tier               = var.redis_tier            # BASIC | STANDARD_HA
  memory_size_gb     = var.redis_memory_size_gb
  region             = var.region
  redis_version      = "REDIS_7_2"
  authorized_network = "projects/${var.project_id}/global/networks/${var.vpc_network}"
  connect_mode       = "DIRECT_PEERING"

  # AOF everysec 유사 정책 — RDB + AOF
  persistence_config {
    persistence_mode        = "RDB"
    rdb_snapshot_period     = "ONE_HOUR"
  }

  # maxmemory-policy는 Memorystore가 관리 (기본: allkeys-lru 유사)
  redis_configs = {
    maxmemory-policy = "allkeys-lru"
  }

  depends_on = [google_project_service.enabled]
}

output "redis_host" {
  value = google_redis_instance.onsafe.host
}

output "redis_port" {
  value = google_redis_instance.onsafe.port
}