# provider.tf
terraform {
  required_version = ">= 1.7.0" # import blocks: variables in id (1.6) and for_each (1.7)
  # Remote state in a NEW dedicated bucket (create it once, see docs/TERRAFORM_LIVE_SYNC.md).
  # Backend blocks cannot use variables; for local-only checks run: terraform init -backend=false
  backend "gcs" {
    bucket = "total-velocity-507606-q2-tfstate"
    prefix = "hkparking"
  }

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