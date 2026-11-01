# Offline Essentials collections

Cina's first downloadable collection is Essentials: pharmacies, hospitals, clinics,
ATMs, toilets, drinking water, train/bus stations, ferry terminals, police,
embassies/consulates, currency exchange, supermarkets and hostels from OpenStreetMap.

## User flow

Open Library → Collections. Find a city through Android's geocoding service, select
a result, then download a 2, 5, 10 or 20 km circle. Coordinate entry is no longer needed or shown.
This is a radius around a point, not an administrative boundary. The screen explains
that city lookup uses the device's geocoding service and area download sends the
chosen coordinates to Overpass. No location permission is needed for either.

Installed areas can be enabled, refreshed or removed. Select an area's centre or
request a fresh phone location, then choose a category and Find offline. Phone
location is obtained only after a button press and permission; there is no background
tracking. Its search reference expires after ten minutes. An out-of-coverage location
is rejected. Distances are straight-line calculations, not routes. Source links open
the external browser; all displayed place details are available offline.

Chat exposes `search_essential_places(query, area?)` as a read-only local tool.
Recognized nearby-place lookup requests with enabled areas are answered directly
from SQLite unless the request explicitly needs online/current information. This
works without a loaded model and takes precedence over a selected model endpoint.
The local tool remains available for other phone-model conversations.
The result names the area, distance reference, snapshot date, record URLs and
coverage limits. Multiple areas require a selection or an explicit matching area.
Named areas absent from the downloaded set produce a coverage error.

## Storage and download behavior

`CollectionCatalog` registers collection kinds. `collections.db` stores versioned
collection metadata and independently indexed place records; it is separate from
personal facts, memories and the existing personal-library backup. This initial
release implements only `osm-essentials`; new kinds need their own acquisition and
retrieval adapter. Unsupported FieldAtlas `.fapack` imports are not offered.

Downloads use an explicit WorkManager request to Private.coffee's HTTPS Overpass
interpreter, an instance listed in OpenStreetMap's [public instance directory](https://wiki.openstreetmap.org/wiki/Overpass_API#Public_Overpass_API_instances).
Only one download is queued/running at once. Queries use an indexed bounding box
and enforce the requested circle locally. They have a 60-second server timeout,
a 20 km maximum radius, a 12 MiB response limit and a 40,000-record parsing limit.
The app rejects partial responses containing an Overpass `remark`, validates
timestamps/coordinates and retains the old installed area until a transaction commits.
The HTTP client has a 20-second connection limit, 80-second read limit and 100-second
whole-call deadline. Cancellation closes the request. Transient network failures and
HTTP 408/429/500/502/503/504 and incomplete Overpass responses get one WorkManager
retry after at least 30 seconds, using overpass-api.de as the backup provider.
Other validation failures do not retry. There are at most two transfer attempts.
Status follows the persisted request ID, with active work taking precedence, rather
than relying on the unspecified ordering of WorkManager history.
Cancelled or failed imports roll back. Valid empty snapshots are allowed and clearly
reported as zero records. Workers can survive navigation/process recreation;
interrupted HTTP requests restart rather than resume byte ranges. Retry is manual
after a service error, avoiding an automatic loop against the public service.

Records retain OSM element IDs, coordinates, available address/hours/accessibility/
contact details, and listing dates. Attribution and the ODbL link appear in the
screen and tool results. A snapshot timestamp describes extraction freshness, not
when each place was surveyed. Missing attributes remain unknown; stored hours do
not establish that a place is currently open. Search never concludes that a place
does not exist simply because the downloaded area has no matching record.

## Scope and follow-ons

The inspiration is FieldAtlas's [Essentials builder](https://github.com/0x94t3z/fieldatlas/blob/main/tools/packs/build_osm_essentials_pack.py)
and [collection catalog](https://github.com/0x94t3z/fieldatlas/blob/main/app/src/main/assets/knowledge/catalog.json).
Its listed worldwide Essentials artifact is roughly 986 MB compressed / 3.6 GB
installed at inspection time. Cina instead starts with smaller user-selected areas;
it does not download or claim compatibility with that artifact, and no FieldAtlas
implementation code is vendored.

Future additions can include curated regional bundles, whole-pack integrity hashes,
resumable static artifacts, and document collections with passage search. Offline
maps/navigation, parsed opening-hours evaluation, full-world search, file-pack import,
are not implemented. Android city search may be absent
on some devices; the screen explains this without offering coordinate entry. Public Overpass availability
is not guaranteed; a distributable regional-pack service would remove that setup dependency.

FieldAtlas's catalog offers useful candidates for later adapters: Vegan places can
reuse spatial lookup; Wikivoyage needs travel-text and place retrieval; Encyclopedia,
Cryptography & Ethereum, and Biology & longevity need a document/passage index and
their own licence, storage and answer-quality checks. None is exposed as an available
download until its format and retrieval path are implemented.

## Verification

`EssentialsTest` covers geometry, malformed/partial snapshots, deduplication, retained
attribution, missing fields, bounds and local tool selection. `CollectionRepositoryTest`
uses real Android SQLite to verify distance ordering, emergency-only filtering,
rollback, deletion, enabled state and coverage/reference checks. `CollectionsScreenTest`
opens the Library collection flow and searches a fixture without loading a model.
`EssentialsDownloadTest` is opt-in with `liveEssentials=true`; it exercises the real
download worker against a 1 km Ottawa area. It is excluded from normal offline checks.

### Earlier validation on 2026-10-31

- Normal arm64 debug APK build and all 111 JVM tests passed; Android lint passed.
- Four Android SQLite tests and the Collections screen test passed in airplane mode
  on a disposable API 36.1 emulator. The results screen was visually inspected.
- The corrected full Overpass query succeeded from the Windows host against
  Private.coffee: 177 records, including 146 nodes with coordinates, no partial-result
  remark. Query output uses `body center`, because `tags` omits node coordinates.
- The Android live-worker smoke test did **not** pass: the original provider returned
  a connection abort/HTTP 502, and Private.coffee timed out at the client's 110-second
  read limit, including after the query correction. The cause has not been isolated.
  Successful on-device downloading is therefore still unverified; do not treat this
  as a fully validated release. No phone model answer-quality evaluation was run.

### Timeout and simplification follow-up

- Replaced the transfer path with OkHttp so cancellation interrupts a stalled request;
  regression checks cover stalled headers, cancellation and retry classification.
  All 109 current JVM tests pass; lint reports no errors (75 existing/project warnings).
- Android HTTPS connectivity to OpenStreetMap succeeds. Public Overpass requests
  timed out from both Android and the Windows host. GET did not resolve it; the final
  implementation uses POST as recommended by the provider. The indexed query and
  final cancellable client still failed the live worker smoke test after its retry.
- Deterministic transfer tests use a local HTTP server, the production downloader,
  real parsing/SQLite installation and search after the server closes. These tests
  are intentionally separate from the opt-in live test and do not establish public
  provider availability.
- Ten Android checks passed with airplane mode enabled: HTTP fixture transfer and
  reopened search, endpoint chat and saved replies, offline chat routing ahead of an
  unreachable endpoint, retirement/data preservation, four SQLite checks and the
  Collections screen. Model navigation, memory migration, Work overview and main
  navigation checks also passed. The endpoint setup screen was visually inspected.
- The arm64 phone debug APK builds successfully. Endpoint protocol tests use a
  compatible HTTP fixture; no live Ollama/llama.cpp model server was supplied for
  model-quality validation.
- **Release gate remains open:** a successful public download followed by a fresh
  process and airplane-mode search is still required. Run the live smoke with
  `liveEssentials=true, keepEssentials=true`, then enable airplane mode and run with
  `verifyOfflineEssentials=true`. Do not count skipped opt-in methods as passing live
  verification. No release was published.

### City input and download reliability verification — 2026-11-01

- Removed coordinate entry. Search by city and country, choose a result, then
  download the selected radius. Editing the city clears the previous selection.
- Track the active WorkManager request and persist its ID for finished status;
  never choose an arbitrary entry from work history. Duplicate active downloads
  report an actionable error instead of silently appearing to start.
- The single delayed retry now uses overpass-api.de. Partial Overpass responses
  also qualify for that retry and never replace installed data.
- Debug app/test APKs build; all 107 JVM tests pass; Android lint passes.
- Six emulator UI, transfer, and repository checks pass. The city form was
  visually inspected. Two additional checks pass for real Ottawa city lookup,
  invalidating a selection when its text changes, and persisted work status.
- The live Ottawa worker download passed in 149 seconds: the first attempt
  requested a retry and the backup attempt succeeded. A fresh app process then
  passed the saved-area search check with airplane mode enabled and Wi-Fi off.
  This closes the earlier live-download verification gap for the tested area;
  public server availability and performance can still vary.
- Evidence is in `.tools/city-download-build-authorized.log`,
  `.tools/city-download-device-tests.log`, `.tools/city-live-download.log`,
  `.tools/city-live-offline.log`, `.tools/city-lookup-status-tests.log`, and
  `.tools/city-download-final-check.log`. No release was published.
