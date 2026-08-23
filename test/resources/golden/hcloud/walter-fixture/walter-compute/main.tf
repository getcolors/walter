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

# The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable.
resource "hcloud_ssh_key" "machine" {
  name       = "walter-fixture"
  public_key = trimspace(file("/home/build-placeholder/.ssh/walter-fixture.pub"))
}

resource "hcloud_server" "node1" {
  name        = "walter-fixture"
  image       = "ubuntu-24.04"
  server_type = "cx23"
  location    = "hel1"
  ssh_keys    = [hcloud_ssh_key.machine.id]
  public_net {
    ipv4_enabled = true
    ipv6_enabled = false
  }
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
    private_key = file("/home/build-placeholder/.ssh/walter-fixture")
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
    ssh_key_id = hcloud_ssh_key.machine.id
  }
}
