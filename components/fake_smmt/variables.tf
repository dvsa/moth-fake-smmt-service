variable "aws_region" {
  type    = "string"
  default = "eu-west-1"
}

variable "project" {
  type    = "string"
  default = "vehicle_recalls"
}

variable "bucket_prefix" {
  type  = "string"
}

variable "environment" {
  type = "string"
}

variable "lambda_s3_key" {
  type    = "string"
  default = "default, when I am only creating bucket, I don't need it"
}

variable "lambda_env_vars" {
  type    = "map"
  default = {
    "default" = "default"
  }
}