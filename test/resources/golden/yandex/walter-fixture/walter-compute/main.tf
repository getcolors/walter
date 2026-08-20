# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = ">= 0.120"
    }
  }
}

provider "yandex" {
  # token comes from YC_TOKEN in the environment
  cloud_id  = "b1gfixturecloud"
  folder_id = "b1gfixturefolder"
  zone      = "ru-central1-a"
}

data "yandex_compute_image" "ubuntu" {
  family = "ubuntu-2404-lts"
}

resource "yandex_vpc_network" "network" {
  name = "walter-fixture"
}

resource "yandex_vpc_subnet" "subnet" {
  name           = "walter-fixture"
  zone           = "ru-central1-a"
  network_id     = yandex_vpc_network.network.id
  v4_cidr_blocks = ["10.0.0.0/24"]
}

resource "yandex_compute_instance" "node1" {
  name        = "walter-fixture"
  platform_id = "standard-v3"
  zone        = "ru-central1-a"

  resources {
    cores         = 2
    memory        = 8
    core_fraction = 100
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.ubuntu.id
      size     = 50
    }
  }

  network_interface {
    subnet_id = yandex_vpc_subnet.subnet.id
    nat       = true
  }

  # Yandex has no account-level SSH key registry: the user and its key are
  # created by cloud-init from instance metadata.
  metadata = {
    ssh-keys = "ubuntu:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBUILDPLACEHOLDER0000000000000000000000"
  }

  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "ubuntu"
    host = self.network_interface.0.nat_ip_address
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
    ip     = yandex_compute_instance.node1.network_interface.0.nat_ip_address
    sudoer = "ubuntu"
    uid    = "1000"
    name   = "walter-fixture"
    user   = "ubuntu"
  }
}
