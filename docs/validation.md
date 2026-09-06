# Validation — 2026-09-05

Built the Android 26+ arm64-v8a/x86_64 APK and emulator launcher through Nix.
APK signatures verified during the build. Runtime checks used an Android 35
x86_64 emulator at 320 × 640, including a live English Wikipedia article.

Passed:

- JVM checks for HTML block separation, infoboxes, table row spans, MathML
  normalization and duplicate fallback removal, safe FTS query terms, serialized
  request admission, request spacing and server cooldown admission.
- Batch identity checks for redirects, missing pages and foreground priority.
- Android SQLite checks for latest/exact revision isolation, aliases, full-text
  indexing, freshness renewal, mark filters/edit/delete and retained anchors.
- Repeated visits, parent paths, paginated history and stored reading offsets.
- Queue deduplication, priority/depth upgrades, continuation state and preservation
  of exploration work when content downloads finish.
- Image accounting, incremental-vacuum mode, WAL checkpoint execution, active
  revision protection, eviction and retention of marks/history after eviction.
- Emulator interaction checks: immediate mark, Undo, section scrub, full contents,
  full history, gesture cancellation, link opening/preview and Android Back.
- Live Wikipedia search and parsing; cached reopening after process restart.

These are correctness and smoke checks, not a representative performance study.
No 16 GiB dataset benchmark, battery study, real-hand ergonomic study or exhaustive
Wikipedia template/math/accessibility coverage has been completed. Tests exposed
and led to fixes for nullable SQL arguments, unstepped PRAGMA statements, menu
placement and duplicate MathML annotation/fallback output.

2026-09-05 handset cache/image follow-up:

- Initial handset snapshot contained 52 revisions and zero images; 1,177 image
  jobs waited behind 1,215 article jobs. Visited titles had valid latest pointers.
- Added and passed Android tests for fair queue class rotation, newest-context
  priorities above Int.MAX_VALUE, delayed jobs and empty queue classes.
- Added and passed parser checks for thumbnail URL normalization, duplicate image
  identities, decoded captions and lead-photo selection past small icons.
- Restored the handset cache into an emulator, disabled Wi-Fi and mobile data,
  and verified article reopening at its stored position. Enabled networking,
  verified the lead photo rendered, then restarted offline and verified the
  photo persisted. Vertical rail scrubbing moved to the expected article area.
- Installed the update on Pixel 9 Pro. Verified every backed-up database,
  preference and image file byte-for-byte after the signing-key transition;
  snapshot integrity passed (134 revisions, four visits). Confirmed the lead
  photo and floating rail on the handset and observed image files accumulating.
- Final APK passed Android storage/queue instrumentation; git diff check passed.

Cached-open and priority follow-up:

- Reproduced contention with both network workers occupied and the library
  monitor held. Removing synchronization from cached() alone failed: obtaining
  the SQLiteOpenHelper connection reacquired the same monitor. Keeping the open
  connection fixed that path; lazy initialization shares the helper's monitor
  to avoid a cold-start lock-order inversion.
- Final Android regression holds a real nonexclusive SQLite write transaction,
  blocks both network workers, and sets API cooldown. Eleven local reads succeed
  without queue writes; visit insertion and position updates persist in order
  after the writer releases. The 32,470-character fixture measured 150–157 ms
  on first parse and about 0.32–0.33 ms warm median. These measure repository
  delivery on this emulator, not full-frame latency or a representative corpus.
- New context/distance ordering, fresh-page exclusions (including aliases), and
  v1-to-v2 queue migration pass. Cached HTML supplies link discovery; the uncached
  fallback requests titles only, preventing revision checks of fresh children.
- Final APK exercised offline link opening and Android Back, then installed with
  adb install -r on the Pixel 9 Pro. Existing app data was retained; a pre-update
  backup is available locally. Parser/request-gate JVM tests and diff checks pass.

Thumb control and persistent preparation follow-up (2026-09-05):

- Replaced the rail with an inset overlay button. Native gesture tests pass for
  return-to-origin cancellation and exact word marks without text highlighting.
  Range tests cover 1 through 10,000 targets, endpoint reachability, narrowing
  around the current selection and widening without selection jumps. Emulator
  checks exercised contents, focused section scrubbing and the search action.
- Cached-link instrumentation verifies cached/uncached underline state and local
  availability updates. Visible link checks use indexed batches off the UI thread.
- Android checks pass for schema v1/v2 to v3 migrations, persistent block encoding
  including large UTF-8 fields, corrupt/version-mismatched fallback, atomic body
  publication, revision eviction and retained marks/history. Existing storage,
  queue, foreground contention and ordered history tests also pass.
- Offline emulator reproduction using the handset's cached Slavic languages
  article measured 1,812 ms parsing and 1,870.75 ms to first pre-draw before the
  change. Parser cleanup alone still took 1,685 ms parsing. With persistent native
  blocks, ten fresh-process runs had median preparation 19.5 ms (best 17 ms) and
  median request-to-pre-draw 235.71 ms (best 223.61 ms). These are one-article
  emulator measurements, not a corpus or 16 GiB cache benchmark.
- Installed on Pixel 9 Pro using install -r after a private local backup. The old
  cached article's one-time conversion took 13,781 ms on the sleeping handset;
  a subsequent fresh process read the persistent blocks in 217 ms, with repository
  delivery totaling 411 ms and no network wait. The handset remained locked, so
  these are repository timings, not verified visible-frame timings. Existing
  cached neighbors prepare locally in the background; new prefetches publish
  their prepared representation with the body.
