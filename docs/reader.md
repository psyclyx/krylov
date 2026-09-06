# Reader behavior

Krylov is an English Wikipedia reader using Android text and view controls, with
SQLite storage. Wikipedia parses wikitext (including templates); the app converts
the resulting HTML into native paragraphs, headings, links, images and tables.
No WebView or remote JavaScript is used.

## One-handed interaction

The persistent control is a quiet 48 dp thumb button, inset 40 dp from the right
edge and 160 dp above the bottom inset. It overlays the article and does not
reserve a toolbar or capture gestures starting at Android's screen edge.

- Tap opens contents; hold opens tools. Drag right and release opens search.
- Drag up to select history, then scrub sideways through visits. All history is
  also a target for reaching the full date-filterable view.
- Drag left to select navigation, then scrub vertically through sections and
  marks in article order. Release jumps to the selected target.
- Drag down to place a mark, then scrub sideways to choose its location in the
  visible text. Pull farther down to move from section to paragraph to word.
- Selection is previewed, without changing scroll position or writing a mark.
  Returning within 22 dp of the original touch cancels; release elsewhere
  commits. Android cancellation, Back, and leaving the activity cancel too.

Navigation begins with every target in range. Outward 34 dp detents capture a
smaller range around the current selection, approximately the square root of
the previous target count down to five targets. Each capture reanchors the
cross axis at the finger, so zooming does not jump the selection. Inward motion
restores the preceding range while retaining the current target. Six dp
hysteresis prevents oscillation at detent boundaries. This is path-dependent
range narrowing, not a fixed mapping from finger distance to article position.

Marks use colored margin dots at the anchored text line; stored marks do not
highlight or recolor article text. The preview names the selected section,
paragraph, or word and indicates its line in the margin. Native text selection
remains available for marking a phrase. Mark category and note editing are
available after the immediate action.

The gesture thresholds and right-hand placement are prototype defaults, not results of a handset study.
They need testing with different hands, screen sizes, accessibility settings and
Android gesture navigation. The emulator does not establish one-handed comfort.

Holding an article link opens a preview; dismissing it adds no visit. Links open
in the same reader. The prior article's revision, block and pixel
offset are preserved for Back. A visit records the parent visit, rather than
merely the parent article; repeated visits and branches remain distinguishable.
Ordinary opens prefer recent cached content; an old cached copy remains readable
while refresh runs. Reading text is not replaced under the reader. Revision
history offers an explicit latest action and historical revision selection.

Infobox details start collapsed; their first substantial image remains visible. Native tables support row/column spans,
horizontal scrolling and incremental row disclosure. Contents includes section
anchors and marks. Text selection offers Mark; holding a heading marks that
section. Ctrl+K/F/M/H invoke search/find/mark/history on hardware keyboards.

## Marks

A mark is an event: article title, exact revision, block, text offsets, quote,
category, optional note and creation time. Marking is immediate; category creation
and notes are available afterward. Editing a mark retains its original timestamp.
Quotes never silently migrate to another revision. A filtered category can be
retraced newest to oldest, fetching the exact marked revision if it was evicted.
Deleting a mark is distinct from evicting content.

## Storage and fetching

- Compressed immutable revision bodies; a separate latest pointer and freshness
  timestamp; title/redirect aliases; FTS4 index for latest cached article text.
- Default freshness: seven days, configurable. Default cache budget: 16 GiB,
  configurable. Images live in an accounted file store; SQLite holds metadata.
- Reclaimable content is evicted automatically. Marks, quote anchors, categories
  and visit history survive. The budget is a target for total local storage:
  permanent metadata and the active revision can prevent reaching it exactly.
- One-hop prefetch by default, unmetered networks by default, persistent deduplicated
  queue with pause, retry and inspection controls. Android JobScheduler resumes
  work; the foreground app also drains the queue.
- Interactive cache misses use one `action=parse&prop=text|revid` request.
- Cached HTML supplies the current article's one-hop links without a discovery
  request. Only stale or missing pages enter pipe-separated revision batches.
  Fresh pages are excluded even if old queue entries remain. Generator discovery
  is a fallback for old exploration jobs whose source HTML is no longer cached.
  Unchanged stale revisions renew freshness without downloading content.
- A shared gate permits one request in flight and at most two starts per second,
  with no accumulated bursts. Interactive requests take admission priority.
  Background API calls send `maxlag=5`. HTTP 429/503 and API load errors set durable
  cooldowns, honoring Retry-After and exponential backoff. No arbitrary daily cap.
- Download failures retry with backoff and stop automatic attempts after six
  failures. Deferred server cooldowns do not consume that attempt allowance.

API references checked September 2026:
[etiquette](https://www.mediawiki.org/wiki/API:Etiquette),
[rate limits](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits),
[parse](https://www.mediawiki.org/wiki/API:Parsing_wikitext),
[revisions](https://www.mediawiki.org/wiki/API:Revisions),
[continuation](https://www.mediawiki.org/wiki/API:Continue).

## Current boundaries

This is a first native implementation, not a finished replacement for Wikipedia's
renderer. Complex templates, nested tables, SVG math and unusual anchor layouts
need broader article coverage. Source/contributor links remain available. History
and graph use bounded windows with access to earlier windows; the graph does not
load a year's worth of nodes at once. Search combines prefix-token FTS with online
Wikipedia results; local relevance is basic. Export/sync and a measured
multi-gigabyte benchmark are not implemented yet. A revision ID identifies the
page revision; refetching it does not freeze historical versions of transcluded
templates or external image files.

Queue priority is newest reading context, then distance from that context.
Article downloads and images rotate fairly at equal rank. Current article
images and direct article links are one hop; linked articles' images are two.
Revisiting cached HTML promotes its missing neighbors again without contacting
Wikipedia. Explicit article misses retain foreground request admission.

Cached article lookup and parsing use a dedicated local executor, independent of
network workers and the writer monitor. Queue insertion, LRU touches and eviction
run after the read path. Parsed revisions have a versioned, compressed SQLite
representation and a 16 MiB bounded memory cache. Prefetch prepares native blocks
before publishing the cached body. Older cached bodies convert once on demand;
a background worker also prepares cached direct neighbors locally, following the
newest reading context without making API requests. Invalid projections fall
back to parsing and are repaired; eviction removes them with the revision.
Fresh ordinary opens perform no revision check; stale copies can refresh in the
background, and explicit latest/revision actions can request the API.

Static Wikimedia image identities omit parser tracking queries. Leading infobox
photos display independently of expanded details.

Visit IDs and the active path are held in memory as soon as an article opens.
Visit insertion and position updates persist in order on a separate executor;
rendering does not await a background SQLite writer. As with other asynchronous
persistence, abrupt process termination can lose a write that has not committed.

Internal article links have a faint dashed underline while no local body is
known, and a faint solid underline when a cached body is available. Visible
links are checked asynchronously in indexed batches, including title aliases
and explicit revisions. The indicator updates as the cache changes; no request
is made just to determine local availability. External links retain a regular
underline. An unknown cache state is displayed dashed until the lookup finishes.

Opening another article keeps the existing text on screen. A small cancelable
loading message appears if necessary; there is no full-screen opening placeholder.
Title-free timing diagnostics separate local lookup, preparation, network/gate
wait, and first draw.
