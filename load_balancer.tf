resource "google_compute_region_network_endpoint_group" "api" {
  name                  = "parking-api-neg"
  region                = var.region
  network_endpoint_type = "SERVERLESS"

  cloud_run {
    service = google_cloud_run_v2_service.backend.name
  }

  depends_on = [
    google_cloud_run_v2_service_iam_member.backend_load_balancer_invoker
  ]
}

resource "google_compute_security_policy" "api" {
  name        = "parking-api-cloud-armor"
  description = "Per-source-IP rate limit for the parking API."

  rule {
    action      = "throttle"
    priority    = 1000
    description = "Allow up to the configured per-IP request rate."

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }

    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"

      rate_limit_threshold {
        count        = var.cloud_armor_rate_limit_per_ip_per_minute
        interval_sec = 60
      }
    }
  }

  rule {
    action      = "allow"
    priority    = 2147483647
    description = "Default allow; rate limiting is enforced by the higher-priority rule."

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
  }
}

resource "google_compute_backend_service" "api" {
  name                  = "parking-api-backend"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  protocol              = "HTTP"
  security_policy       = google_compute_security_policy.api.id
  custom_request_headers = [
    "X-Load-Balancer-Client-IP: {client_ip_address}"
  ]

  backend {
    group = google_compute_region_network_endpoint_group.api.id
  }
}

resource "google_compute_url_map" "api" {
  name            = "parking-api-url-map"
  default_service = google_compute_backend_service.api.id
}

resource "google_compute_managed_ssl_certificate" "api" {
  name = "parking-api-certificate"

  managed {
    domains = [var.api_domain]
  }
}

resource "google_compute_target_https_proxy" "api" {
  name             = "parking-api-https-proxy"
  url_map          = google_compute_url_map.api.id
  ssl_certificates = [google_compute_managed_ssl_certificate.api.id]
}

resource "google_compute_global_address" "api" {
  name = "parking-api-ip"
}

resource "google_compute_global_forwarding_rule" "api_https" {
  name                  = "parking-api-https"
  ip_address            = google_compute_global_address.api.address
  ip_protocol           = "TCP"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  port_range            = "443"
  target                = google_compute_target_https_proxy.api.id
}
