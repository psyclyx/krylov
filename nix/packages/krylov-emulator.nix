{
  writeShellApplication,
  coreutils,
  gnugrep,
  jdk17,
  krylov-sdk,
  krylov,
}:
writeShellApplication {
  name = "krylov-emulator";
  runtimeInputs = [
    coreutils
    gnugrep
    jdk17
    krylov-sdk
  ];
  text = ''
    export JAVA_HOME=${jdk17}
    export ANDROID_HOME=${krylov-sdk}/libexec/android-sdk
    export ANDROID_USER_HOME
    ANDROID_USER_HOME=$(mktemp -d -t krylov-android-XXXXXX)
    export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
    mkdir -p "$ANDROID_AVD_HOME"
    emulator_pid=
    cleanup() {
      if [[ -n "$emulator_pid" ]]; then
        kill "$emulator_pid" 2>/dev/null || true
        wait "$emulator_pid" 2>/dev/null || true
      fi
      rm -rf "$ANDROID_USER_HOME"
    }
    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    port=''${KRYLOV_EMULATOR_PORT:-5554}
    if [[ ! "$port" =~ ^[0-9]+$ ]] || ((port < 5554 || port > 5682 || port % 2)); then
      echo "KRYLOV_EMULATOR_PORT must be an even port from 5554 to 5682." >&2
      exit 1
    fi
    export ANDROID_SERIAL="emulator-$port"
    adb start-server
    if adb devices | grep -q "^''${ANDROID_SERIAL}[[:space:]]"; then
      echo "Port $port already has an emulator; set KRYLOV_EMULATOR_PORT to another even port." >&2
      exit 1
    fi
    echo no | avdmanager create avd --force --name krylov \
      --package 'system-images;android-35;default;x86_64'
    "$ANDROID_HOME/emulator/emulator" -avd krylov -port "$port" \
      -no-snapshot -no-boot-anim -gpu swiftshader_indirect "$@" &
    emulator_pid=$!
    echo 'Waiting for Android to boot…'
    deadline=$((SECONDS + ''${KRYLOV_BOOT_TIMEOUT:-300}))
    until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
      if ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo 'Android emulator exited before boot completed.' >&2
        exit 1
      fi
      if ((SECONDS >= deadline)); then
        echo 'Android boot timed out.' >&2
        exit 1
      fi
      sleep 2
    done
    adb install -r ${krylov}/krylov.apk
    adb shell am start -W -n dev.psyclyx.krylov/.MainActivity
    echo "Krylov is running on $ANDROID_SERIAL. Close the emulator or press Ctrl-C to stop."
    wait "$emulator_pid"
  '';
}
