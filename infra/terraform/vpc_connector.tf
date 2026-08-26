# Serverless VPC Access Connector — Cloud Run ↔ Memorystore 프라이빗 통신
resource "google_vpc_access_connector" "onsafe" {
  name          = var.vpc_connector_name
  region        = var.region
  network       = var.vpc_network
  ip_cidr_range = var.vpc_connector_ip_cidr

  # 최소 사양 (기본). 트래픽 늘어나면 min/max_throughput 조정
  min_throughput = 200
  max_throughput = 300

  depends_on = [google_project_service.enabled]
}

output "vpc_connector_id" {
  value = google_vpc_access_connector.onsafe.id
}