terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 8.4.0" # Using the modern 5.x branch
    }
  }
}

provider "oci" {
  config_file_profile = "DEFAULT"
}

data "oci_core_subnet" "public_subnet" {
  subnet_id = "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaafixturesubnet"
}

# The image is pinned, so there is no lookup at all. source_id is ForceNew on
# oci_core_instance, and left to a data source it is whatever Canonical
# published most recently — meaning a routine apply, months after the last one,
# proposes destroying the VM because an image appeared that nobody asked for.

resource "oci_core_instance" "ampere_vm" {
  availability_domain = "XquT:EU-FRANKFURT-1-AD-1"
  compartment_id      = "ocid1.tenancy.oc1..aaaaaaaafixturecompartment"
  display_name        = "walter-fixture"
  shape               = "VM.Standard.A2.Flex"
  shape_config {
    ocpus         = 2
    memory_in_gbs = 12
  }
  create_vnic_details {
    subnet_id        = data.oci_core_subnet.public_subnet.id
    assign_public_ip = true
  }
  source_details {
    source_type             = "image"
    source_id               = "ocid1.image.oc1.eu-frankfurt-1.aaaaaaaafixtureimage"
    boot_volume_size_in_gbs = 100
    boot_volume_vpus_per_gb = 30
  }
  metadata = {
    ssh_authorized_keys = file("/home/build-placeholder/.ssh/walter_walter-fixture.pub")
  }
  connection {
    type = "ssh"
    user = "ubuntu"
    host = self.public_ip
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
    ip = oci_core_instance.ampere_vm.public_ip
    sudoer = "ubuntu"
    uid = "1001"
    name = "walter-fixture"
    user = "ubuntu"
  }
}
