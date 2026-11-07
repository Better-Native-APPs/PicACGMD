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

        buildToolsVersion = "36.0.0";
        platformVersion = "36";

        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ platformVersion ];
          buildToolsVersions = [ buildToolsVersion ];
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "google_apis" ];
          abiVersions = [ "x86_64" ];
          includeNDK = false;
          includeSources = false;
        };

        sdk = android.androidsdk;
        sdkRoot = "${sdk}/libexec/android-sdk";
        jdk = pkgs.jdk21;
      in
      {
        devShells.default = pkgs.mkShell {
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
      });
}
