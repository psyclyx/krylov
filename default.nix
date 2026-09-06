let
  npins = import ./npins;
  mkPackages =
    lib: pkgs:
    lib.packagesFromDirectoryRecursive {
      inherit (pkgs) callPackage;
      directory = ./nix/packages;
    };
  overlay = final: prev: mkPackages prev.lib final;
in
{
  nixpkgs ? npins.nixpkgs,
  pkgs ? import nixpkgs {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  },
}:
let
  finalPkgs = pkgs.extend overlay;
  packages = mkPackages pkgs.lib finalPkgs;
in
{
  inherit packages overlay;
  default = packages.krylov;
  apk = packages.krylov;
  emulator = packages.krylov-emulator;
  shell = finalPkgs.callPackage ./nix/shell.nix { };
}
