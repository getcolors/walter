# Vultr power verbs address the instance by immutable UUID, never by its label.
# OpenTofu merges this file with ONCE's provider template.
output "instance_id" {
  value = vultr_instance.node1.id
}
