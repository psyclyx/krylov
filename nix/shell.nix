{
  mkShell,
  kotlin,
  jdk17,
  krylov-sdk,
  cmake,
  ninja,
  npins,
}:
mkShell {
  packages = [
    kotlin
    jdk17
    krylov-sdk
    cmake
    ninja
    npins
  ];
  JAVA_HOME = "${jdk17}";
  ANDROID_HOME = "${krylov-sdk}/libexec/android-sdk";
  ANDROID_NDK_HOME = "${krylov-sdk}/libexec/android-sdk/ndk/29.0.14206865";
}
