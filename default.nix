{ pkgs ? import <nixpkgs> {} }:

with pkgs;

let
  buildGradle = callPackage ./gradle-env.nix {};

  gradle2nix = buildGradle {
    envSpec = ./gradle-env.json;

    src = lib.cleanSourceWith {
      filter = lib.cleanSourceFilter;
      src = lib.cleanSourceWith {
        filter = path: type: let baseName = baseNameOf path; in !(
          (type == "directory" && (
            baseName == "build" ||
            baseName == ".idea" ||
            baseName == ".gradle"
          )) ||
          (lib.hasSuffix ".iml" baseName)
        );
        src = ./.;
      };
    };
    buildJdk = pkgs.jdk17;

    gradleFlags = [ "installDist" ];
    enableParallelBuilding = true;

    installPhase = ''
      mkdir -p $out
      sed -i "s/DEFAULT_JVM_OPTS='\(.*\)'/DEFAULT_JVM_OPTS=\1/" app/build/install/gradle2nix/bin/gradle2nix

      cp -r app/build/install/gradle2nix/* $out/
    '';

    passthru = {
      plugin = "${gradle2nix}/share/plugin.jar";
    };
  };

in gradle2nix
