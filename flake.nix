{
  description = "ccusage-focus: convert ccusage daily JSON into FOCUS 1.2 CSV";

  # Same nixpkgs revision as ccusage's flake.lock at the time this was written,
  # so the converter and the ccusage dev shell share one package set.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/8edc0c72e3a38faf5434e40d1f19431125fe4b30";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAll (pkgs: {
        default = pkgs.mkShell { packages = [ pkgs.babashka ]; };
      });
    };
}
