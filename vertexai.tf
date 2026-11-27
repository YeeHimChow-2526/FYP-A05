# vertexai.tf

# 1. If you are using a custom Vertex AI Endpoint or Model, define it here:
# resource "google_vertex_ai_endpoint" "agent_endpoint" {
#   name         = "rag-agent-endpoint"
#   display_name = "RAG Agent Endpoint"
#   location     = var.region
#   depends_on   = [google_project_service.required_apis]
# }

# 2. Output relevant Vertex AI details for other modules/Cloud Run to use
output "vertex_ai_location" {
  value       = var.region
  description = "Region where Vertex AI services are deployed"
}

# output "vertex_ai_endpoint_id" {
#   value       = google_vertex_ai_endpoint.agent_endpoint.id
#   description = "The Vertex AI Endpoint ID for the agent"
# }