terraform {
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }
}

provider "digitalocean" {
  # token comes from DIGITALOCEAN_TOKEN in the environment
}

resource "digitalocean_droplet" "node1" {
  name     = "walter-fixture"
  region   = "ams3"
  size     = "s-1vcpu-1gb-35gb-intel"
  image    = "ubuntu-25-10-x64"
  vpc_uuid = "00000000-0000-0000-0000-000000000000"

  # SSH Keys are passed as a list of IDs or Fingerprints
  ssh_keys = ["fixture-key"]
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
    ip = digitalocean_droplet.node1.ipv4_address
    sudoer = "root"
    name = "walter-fixture"
    user = "root"
  }
}
