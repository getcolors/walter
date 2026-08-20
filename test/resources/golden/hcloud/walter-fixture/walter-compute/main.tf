# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

provider "hcloud" {
  # token comes from HCLOUD_TOKEN in the environment
}

resource "hcloud_server" "node1" {
  name        = "walter-fixture"
  image       = "ubuntu-24.04"
  server_type = "cx23"
  location    = "hel1"
  ssh_keys    = ["${hcloud_ssh_key.walter.name}"]
  public_net {
    ipv4_enabled = true
    ipv6_enabled = false
  }
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle {
    prevent_destroy = true
  }
}

output "params" {
  value = {
    ip = hcloud_server.node1.ipv4_address
    sudoer = "root"
    name = "walter-fixture"
    user = "root"
  }
}
