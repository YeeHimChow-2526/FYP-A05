# apis.tf
resource "google_project_service" "required_apis" {
  for_each           = toset(var.project_services)
  service            = each.key
  disable_on_destroy = false
}