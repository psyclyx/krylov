# Krylov

A native Android Wikipedia reader. Articles open from a local SQLite cache, with
full-text search, cached images, revision-aware marks and a record of the paths
you took between articles.

The reader has one thumb control: tap to mark, hold for tools, scrub sideways for
recent articles or upwards for sections. Pull farther to open history or contents.
Android handles Back. Infoboxes stay collapsed until needed.

## Run

```sh
nix-build -A emulator
./result/bin/krylov-emulator
```

The launcher boots Android 35, installs the APK and opens Krylov. Each run uses a
fresh temporary device, removed on exit. KVM is recommended. Emulator arguments
are forwarded, such as `-no-window -no-audio` for headless use.
`KRYLOV_EMULATOR_PORT` defaults to 5554; `KRYLOV_BOOT_TIMEOUT` to 300 seconds.

```sh
nix-build -A apk                  # result/krylov.apk
nix-shell                        # Kotlin, JDK, Android SDK / NDK
```

Android 26+, arm64-v8a and x86_64. Nix compiles Kotlin, DEX and the existing C++
JNI scaffold directly; no Gradle or Maven downloads. APK builds create an
ephemeral development key (password `android`) inside the build. Development
updates preserve app data with `adb install -r` when the same locally retained
key is used; releases need a private signing key.

## Data and network policy

- Configurable freshness (7 days by default) and cache budget (16 GiB by default).
- One-link prefetch, persistent queue, unmetered networks by default.
- Local link discovery, stale-only revision checks with MediaWiki `|` title
  batches. Unchanged content is not downloaded again.
- One network request at a time, at most two starts/second, foreground priority,
  background `maxlag=5`, server cooldowns and backoff. No daily request cap.
- Eviction preserves history, marks, quotes and notes. A marked revision can be
  fetched again when its content has been evicted.

See [reader behavior and current boundaries](docs/reader.md) for interaction,
storage and API details. This is an initial implementation; broad article-format
coverage and measured multi-gigabyte performance remain validation work.

## Core checks

Inside `nix-shell`:

```sh
kotlinc src/main/kotlin/dev/psyclyx/krylov/Article.kt \
  src/main/kotlin/dev/psyclyx/krylov/MathText.kt \
  src/main/kotlin/dev/psyclyx/krylov/RequestGate.kt \
  src/test/kotlin/dev/psyclyx/krylov/CoreTests.kt \
  -include-runtime -d /tmp/krylov-tests.jar
java -jar /tmp/krylov-tests.jar
```

The APK build also produces a separately installable instrumentation APK. With
the emulator running and both APKs from the same build installed:

```sh
adb shell am instrument -w \
  dev.psyclyx.krylov.tests/.LibraryInstrumentation
```

The tests use isolated storage names and cover revision pointers, FTS, marks,
visit paths/positions, queue upgrades, image accounting and eviction. Passing
`-e seed true` explicitly adds offline test articles to the reader and disables
prefetch for UI testing.

`default.nix` exposes `apk`, `emulator`, `shell`, `packages` and `overlay`, accepts
`nixpkgs` or `pkgs`, and otherwise uses the npins pin. Update it with
`npins update nixpkgs`.
