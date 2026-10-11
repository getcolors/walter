{ pkgs, ... }:
{
  languages.python.enable = true;
  packages = [ pkgs.uv pkgs.openssh pkgs.google-cloud-sdk pkgs.gh pkgs.git ];
}
