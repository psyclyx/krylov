# Krylov

A Kotlin Android app with a C++ JNI library built by the Android NDK.
The starter screen displays a greeting returned by native code.

```sh
nix-build -A emulator
./result/bin/krylov-emulator
```

The build produces a launcher; running it boots an Android 35 emulator,
installs the built APK, and opens Krylov. Close the emulator or press Ctrl-C
to stop it. Each run uses a fresh temporary device, removed on exit.
The first build downloads the SDK, NDK, and system image.
The launcher targets x86_64 Linux; enable KVM for usable emulator performance.
Emulator arguments are forwarded, for example `-no-window -no-audio` for
headless use. `KRYLOV_EMULATOR_PORT` defaults to 5554; `KRYLOV_BOOT_TIMEOUT`
defaults to 300 seconds.

```sh
nix-build -A apk                  # result/krylov.apk
nix-shell                        # Kotlin, JDK, SDK, NDK, CMake, Ninja, npins
# Or: nix-shell default.nix -A shell
```

The APK includes x86_64 and arm64-v8a native libraries and supports Android
26 or newer. This scaffold compiles Kotlin, DEX, and CMake directly in Nix;
it does not use Gradle or fetch Maven dependencies. The APK is debuggable and
signed with a generated development key, not suitable for release. A new
build can change the signing key; uninstall a previous device installation
before installing a differently signed build.

`src/main/kotlin/` owns the activity, `src/main/cpp/` owns native code, and
`src/main/AndroidManifest.xml` owns Android metadata.

`default.nix` accepts `nixpkgs` or an instantiated `pkgs`, exposes
`packages`, `overlay`, `apk`, `emulator`, `default`, and `shell`, and otherwise
uses `npins/nixpkgs`. The default import enables the Android SDK license and
unfree SDK tools. Callers supplying `pkgs` must enable
`config.android_sdk.accept_license` and `config.allowUnfree` themselves.
Update the standalone pin with `npins update nixpkgs`.
