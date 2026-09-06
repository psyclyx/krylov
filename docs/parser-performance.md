# Article parser investigation

The initial Android emulator trace for a cached **Slavic languages** revision was
13 ms in cache lookup, 1,812 ms in `ArticleBlocks.parse`, and about 33 ms from the
prepared result to drawing. Parsing, rather than a cache miss, explained this
reproduction. These are diagnostic phase measurements, not an Android A/B result.

The first change hoisted the entity regular expression out of the per-attribute
decoder. On the host, that alone reduced median parsing time by roughly 10–18%
across four saved articles. The retained follow-up removes more repeated work:

- Compile fixed regexes and construct tag-membership sets once.
- Bypass entity matching for attribute values without an ampersand.
- Read attribute capture groups without allocating an intermediate list.
- Avoid attribute parsing on closing tags and inside discarded subtrees.
- Extract tag names without copying the remainder of each tag twice.
- Reuse the MathML tokenizer and avoid a per-node annotation set.

The standalone harness in `src/bench/kotlin/ParserBenchmark.kt` loads the reference
and candidate parser jars in separate class loaders. It first checks six
reference outputs for determinism, checks six candidate outputs against that
oracle, and then interleaves 12 samples per implementation with reversed order on
alternating rounds. Every timed output is checked. The block digest includes the
kind, HTML, anchor, and image source with length delimiters. Supplemental checks
compare images, lead images, article links, and table output.

The completed host run for the combined changes yielded:

| Saved article | Reference median / best (ms) | Candidate median / best (ms) |
| --- | --- | --- |
| Slavic languages | 12.16 / 10.29 | 7.67 / 6.37 |
| Rusyn language | 22.73 / 16.77 | 10.54 / 8.83 |
| Russian language | 21.74 / 20.69 | 12.17 / 11.60 |
| Russia | 72.41 / 71.89 | 40.95 / 40.43 |

These are JVM host timings, not phone timings. Android verification of the
candidate remains necessary. A synthetic fixture covering entities, invalid
numeric entities, hidden markup, nested tables, and MathML also retained identical
block output. An initial benchmark-helper failure on a null lead image was fixed;
that failed synthetic run supplied no performance result.

Compile each parser version's `Article.kt` and `MathText.kt` into a separate jar,
then run the harness against identical saved HTML:

```sh
kotlinc src/bench/kotlin/ParserBenchmark.kt -include-runtime -d benchmark.jar
java -jar benchmark.jar baseline.jar candidate.jar article.html
```

Session reference sources, jars, and extracted fixtures are in
`/tmp/krylov-parser-bench`. The Slavic block-output SHA-256 is
`55b8ed919f8b017a97ebc7e909f394b74b998b6bf5b37910d27f7e0e2c2d3b11`.
