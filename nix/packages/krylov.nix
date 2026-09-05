{ lib, stdenvNoCC, kotlin, jdk17, cmake, ninja, zip, krylov-sdk }:
stdenvNoCC.mkDerivation {
  pname = "krylov";
  version = "0.1.0";
  src = lib.fileset.toSource { root = ../..; fileset = ../../src; };
  nativeBuildInputs = [ kotlin jdk17 cmake ninja zip ];
  dontConfigure = true;
  dontFixup = true;
  buildPhase = ''
    runHook preBuild
    export HOME="$TMPDIR"
    export JAVA_HOME=${jdk17}
    sdk=${krylov-sdk}/libexec/android-sdk
    tools="$sdk/build-tools/37.0.0"
    android="$sdk/platforms/android-35/android.jar"
    mkdir -p dex apk/lib
    kotlinc src/main/kotlin -classpath "$android" -jvm-target 1.8 -include-runtime -d classes.jar
    "$tools/d8" --lib "$android" --min-api 26 --output dex classes.jar
    for abi in x86_64 arm64-v8a; do
      cmake -S src/main/cpp -B "native-$abi" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$sdk/ndk/29.0.14206865/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 \
        -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
      cmake --build "native-$abi"
      mkdir -p "apk/lib/$abi"
      cp "native-$abi/libkrylov.so" "apk/lib/$abi/"
    done
    "$tools/aapt2" link -I "$android" --manifest src/main/AndroidManifest.xml -o unsigned.apk
    cp dex/*.dex apk/
    (cd apk; zip -q -r ../unsigned.apk .)
    "$tools/zipalign" -f -p 4 unsigned.apk aligned.apk
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
      -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
      -keyalg RSA -keysize 2048 -validity 10000
    "$tools/apksigner" sign --ks debug.keystore --ks-pass pass:android \
      --out krylov.apk aligned.apk
    "$tools/apksigner" verify krylov.apk
    runHook postBuild
  '';
  installPhase = ''
    mkdir -p $out
    cp krylov.apk $out/
  '';
  meta = {
    description = "Krylov Kotlin and Android NDK starter app";
    platforms = [ "x86_64-linux" ];
  };
}
