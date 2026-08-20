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

# No pinned image: take the newest one compatible with the shape the instance
# actually launches on. OCI images carry a compatibility list, so filtering on a
# different shape can return an image the instance cannot boot — this read
# A1.Flex while the instance took whatever oci-shape said, and only kept working
# because A1 and A2 images overlap.
#
# Convenient for a first create, and a moving target thereafter: set
# oci-image-id once the stack is real. The resource name is left alone
# deliberately — it is a state address, and renaming it would look like a
# replacement to every existing stack.
data "oci_core_images" "ubuntu_24_04_arm" {
  compartment_id           = "ocid1.tenancy.oc1..aaaaaaaafixturecompartment"
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "VM.Standard.A2.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

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
    source_id               = data.oci_core_images.ubuntu_24_04_arm.images[0].id
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
