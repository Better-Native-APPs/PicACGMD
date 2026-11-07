{
  description = "Pikapika Android — native Kotlin + Jetpack Compose (Material 3 Expressive) PicACG client";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachSystem [ "x86_64-linux" ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        buildToolsVersion = "37.0.0";
        platformVersions = [ "37.0" ];

        mkAndroid = withEmulator: pkgs.androidenv.composeAndroidPackages {
          inherit platformVersions;
          buildToolsVersions = [ buildToolsVersion ];
          # Emulator + API 37 system image (~1.8 GB) only in the `emulator` shell.
          includeEmulator = withEmulator;
          includeSystemImages = withEmulator;
          systemImageTypes = [ "google_apis" ];
          abiVersions = [ "x86_64" ];
          includeNDK = false;
          includeSources = false;
        };

        sdk = (mkAndroid false).androidsdk;
        sdkRoot = "${sdk}/libexec/android-sdk";
        sdkEmu = (mkAndroid true).androidsdk;
        sdkEmuRoot = "${sdkEmu}/libexec/android-sdk";
        jdk = pkgs.jdk21;

        mkDevShell = sdk: sdkRoot: pkgs.mkShell {
          packages = [
            sdk
            jdk
            pkgs.gradle
            pkgs.kotlin
            pkgs.kotlin-language-server
            pkgs.ktlint
            # design toolchain (design/src: HTML/CSS mockups -> PNG via headless Chrome)
            pkgs.nodejs
            pkgs.python3
            pkgs.jq
            pkgs.git
          ];

          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          JAVA_HOME = jdk.home;

          shellHook = ''
            # aapt2 downloaded from Maven is dynamically linked and fails on NixOS;
            # point AGP at the Nix-provided binary instead.
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2 $GRADLE_OPTS"
            export ANDROID_AVD_HOME="$PWD/.android/avd"
            export ANDROID_USER_HOME="$PWD/.android"
            export PATH="${sdkRoot}/emulator:${sdkRoot}/platform-tools:$PATH"
          '';
        };
      in
      {
        devShells.default = mkDevShell sdk sdkRoot;
        # `nix develop .#emulator` — same toolchain plus emulator and the API 37 google_apis x86_64 image.
        devShells.emulator = mkDevShell sdkEmu sdkEmuRoot;
      });
}
