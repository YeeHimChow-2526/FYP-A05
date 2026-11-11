# provider.tf
terraform {
  required_version = ">= 1.6.0" # import blocks may use variables in id from 1.6
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}