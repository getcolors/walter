# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    vultr = {
      source  = "vultr/vultr"
      version = "~> 2.0"
    }
  }
}

provider "vultr" {
  # api key comes from VULTR_API_KEY in the environment
}

# The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable.
resource "vultr_ssh_key" "machine" {
  name    = "walter-fixture"
  ssh_key = trimspace(file("/home/build-placeholder/.ssh/walter-fixture.pub"))
}

resource "vultr_instance" "node1" {
  # `label` is the console name and updates in place, which is what every other
  # compute template here sets. There is deliberately no `hostname`: Vultr's API
  # implements a hostname change as an OS reinstall, so the provider marks that
  # attribute ForceNew, and editing vultr-name would destroy the instance and
  # its disk rather than rename it. The attribute is Optional+Computed, so
  # omitting it keeps whatever Vultr assigned and produces no diff on an
  # instance that already exists.
  label  = "walter-fixture"
  region = "ams"
  plan   = "vc2-2c-4gb"
  os_id  = 2284
  # SSH keys are passed as a list of key ids already in the account. This is
  # ForceNew too: changing the key set destroys and recreates the instance
  # instead of re-authorizing it, so a disposable key lasts the life of the
  # deployment -- rotate it by rebuilding, never by editing vultr-ssh-keys on a
  # machine whose disk you intend to keep.
  ssh_key_ids = [vultr_ssh_key.machine.id]
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.main_ip
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
    ip = vultr_instance.node1.main_ip
    sudoer = "root"
    name = "walter-fixture"
    user = "root"
    ssh_key_id = vultr_ssh_key.machine.id
  }
}
