# Local reliability fixes — proposed version 6

These are local software changes. They have not been committed, published, or installed on the Chromecast. Content-selection rules and configured sources have not been changed, and the requested separate extensions have not been created.

## Changes

- Catalogue deduplication now uses URL, host-scoped numeric ID, and Unicode title identities. Tracking parameters and resolution labels do not create extra entries; distinct non-Latin titles and long title suffixes remain distinguishable.
- Duplicate claims are atomic, page-aware, bounded, and temporary. Refreshing a row releases its old claims. Unshown entries beyond the display limit are not reserved. Primary items are reserved before deciding whether more results are needed, so simultaneous rows cannot both mistake shared candidates for available results.
- A link resolver only reports success after actually delivering at least one valid HTTP(S) URL. Empty resolvers, malformed URLs, failed callbacks, and ordinary resolver exceptions no longer produce false success. Cancellation still propagates. This does not prove a delivered URL is playable.
- HTTP error responses are rejected before direct-link extraction. The blanket, unverified HD catalogue label was removed.
- The release probe checks response type, initial file signature, length, and supplied byte ranges. HTML error pages returned with HTTP 200 fail this check. Reads are bounded to 1,024 bytes, and logs no longer claim full playback or filtering verification.
- Release filenames derive from generated version metadata. Stable endpoints and legacy versioned repository/catalogue aliases point to the current package. Publishing preserves older binary files. Package identity, version, ZIP integrity, code presence, size, and hash are checked before publication.

## Verification

1. Run the synthetic Kotlin regression tests with `./gradlew BroStreamFreshByFaz2:testDebugUnitTest` using Java 17 and Android SDK 35.
2. Run the 35 synthetic Python checks with `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v`.
3. Build the local package with `./gradlew makePluginsJson`.
4. Validate release metadata and packages with `scripts/prepare_release.py`, using an output directory that does not already exist.

The tests exercise the local logic with synthetic entries and byte responses. They do not browse media sources. The package build succeeds but emits D8 warnings about rewriting Kotlin metadata with the existing toolchain; successful compilation is not device-runtime verification.

## Remaining limitations

Live catalogue contents, content classification, source availability, full decoding, and Chromecast playback have not been tested during this repair. These changes do not establish that every link works or that content matches the requested preferences. Duplicate detection uses metadata rather than visual matching, so differently named copies with different identifiers can remain. Previously deleted remote binary artifacts are not restored by preserving future releases.

# Version 7 — content policy, categories and playback

- **Men-only evidence.** Every video needs positive male-only evidence (tags, performer metadata, or explicit male wording). Female terms, abbreviations (MMF, GF), names, multilingual words, bisexual and mixed-pairing phrases reject it, matched as whole words. Unclear titles trigger a detail-page read; if still unclear they are quarantined and never shown. No source is trusted by default.
- **Categories.** `Rows.kt` is the category table: feeds, relevance rule, sort rule, minimum item count. The earliest matching row owns an item, so placement never depends on load order. Rows with too few verified videos try other providers, then hide.
- **Time rows.** "New Today" and "Popular This Week" use real upload times and view counts, and hide when the sources do not expose them.
- **Playback.** Declared sources and structured data come before the page-wide regex; entities in signed URLs are decoded; one link per quality with accurate labels; each URL is tested with a small range request before it is offered, and seekable or adaptive streams are preferred.
- **Corrections without a release.** `blocklist.json` (published to the builds branch) blocks videos by ID and words by term.
- **Removed.** MyVidster curated list, Eporner leftovers.

## Release gate (`scripts/release_gate.py`)
Refuses to publish if: versions in `build.gradle.kts`, `plugins.json` and the embedded `.cs3` manifest differ; any content-policy test fails or is missing; the TinyURL in `release/links.json` points elsewhere; any category's feeds are empty; duplicate level exceeds 0.35; or the first poster, a detail page, or a byte-range video request fails for any source. Set the short link in `release/links.json` to enable that check.

## Known limits
Site selectors for dates, views, tags and performer gender are unverified against the live sites (they were unreachable during development); run the "Audit source metadata" workflow and adjust. The gate's category check counts listing cards; the Kotlin filters are covered by unit tests, not by live runs.
