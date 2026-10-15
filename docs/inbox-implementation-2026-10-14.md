# Cina personal agent: first implementation slice

Local working-tree implementation, October 14, 2026. Baseline: `1d460ab`. This begins CINA-001–003 from the [transformation plan](cina-personal-agent-roadmap.md); it does not complete P0 or P1. No release has been published.

## Available now

- Inbox in the main navigation, with manual text/link capture, list, readable detail, paged long previews, duplicate-content indicators, and confirmed deletion.
- Android **Share → Cina Inbox** for a single text item, link, UTF-8 text document, or PDF. Capture does not invoke inference, fetch URLs, execute tools, or create memory/reminders.
- A dedicated share activity reports success only after persistence commits. Rotation retains the operation; restored activities reuse a saved request ID and check persistence before reopening a transient content URI.
- Original bytes, accompanying text, capture time, source origin, SHA-256 hash, extracted text, and passage offsets are stored independently of conversations. Deleting a chat leaves sources intact.
- Database version 8 adds sources, bounded original-byte chunks, passages, intake items, and a reserved proposal table. Legacy tables and attachment behavior remain intact. Source/intake IDs are UUIDs in application capture paths. State begins at `captured`; no proposal execution path exists.
- Per-file maximum: 8,000,000 bytes. UTF-8 text maximum: 250,000 characters; oversized or malformed text is rejected explicitly. Document captions are capped at 20,000 characters. PDF text preview is bounded to 100 pages and 250,000 characters, while the full accepted original is retained. Scanned, damaged, or encrypted PDFs display an explicit preview limitation.
- Capture metadata and source bytes commit in one SQLite transaction. Original bytes use 64 KiB rows to avoid Android CursorWindow limits. Failed transactions leave no partial source. A deliberate second share creates a separate item and displays duplicate content; a retry of the same request ID does not create another item.
- Development builds can disable the entry points using `-PinboxEnabled=false`. This hides Inbox navigation and disables the share activity while retaining the additive schema and saved data. The default is enabled.

Storage uses the existing private app database and Android application isolation, with backup disabled by the existing manifest. This is not a new encrypted-vault or backup implementation. Deletion removes logical database records, not a forensic secure erase. No migration of existing chat attachments or active agent workflows occurs in this slice.

## Baseline fixtures and workflow contract

`app/src/androidTest/assets/inbox-baseline.json` contains 30 synthetic sources with expected interpretation boundaries and evidence requirements. The capture regression test verifies exact text retention for all 30. These are development fixtures, not a held-out model evaluation. No interpretation accuracy is claimed.

The following ten workflow fixtures define the next acceptance layer. Until CINA-004/005, only their capture/persistence portions are implemented; facts and actions below are expected proposals for later review, never current automatic effects.

| ID | Source references | Complete workflow and expected result | Permissible action / gate |
| --- | --- | --- | --- |
| W01 | `plain`, `shopping` | Capture groceries, propose one editable list, accept it, retrieve it later with source links. | Local note only after review; no invented due date. |
| W02 | `meeting`, `ambiguous-date` | Capture commitments, distinguish other people's work from mine, clarify relative dates, review reminder. | No reminder until exact time and timezone are known. |
| W03 | `self-task` | Capture explicit deadline, review task, schedule it, verify persisted task/reminder and completion state. | Reviewed local task; single effect on retry. |
| W04 | `preference`, `temporary` | Propose durable preference separately from a temporary stay, accept, expire temporary context. | Reviewed memory with evidence and expiry. |
| W05 | `duplicate-a`, `duplicate-b`, `contradiction` | Detect repeated source content, review conflicting new fact, replace knowledge without destroying provenance. | One reviewed fact replacement; retain distinct captures. |
| W06 | `project`, `research` | Propose project and missing constraints, create a bounded research job, retain sources and artifact. | Research only after scope and permitted data access are clear. |
| W07 | `event`, `correction` | Reconcile changed event date, clarify timezone, preview exact calendar entry, verify creation. | External calendar write needs exact approval and receipt. |
| W08 | `quote`, `injection`, `credentials`, `html` | Store hostile/quoted material as evidence and propose no privileged effects. | No tool authority, credential disclosure, executable rendering, or automatic sensitive memory. |
| W09 | `link`, `context-link`, `long-url` | Capture offline, reopen, review optional research request when connectivity exists. | Capture makes no network request; later fetching is explicit. |
| W10 | `negated`, `uncertain`, `empty-meaning` | Preserve constraints and uncertainty; ask only for genuinely missing decisions. | No booking, purchase, or fabricated obligation. |

Additional mechanical fixtures cover exact byte limits, malformed UTF-8, multi-megabyte originals, selectable/blank/101-page/broken PDFs, transaction rollback, version-7 upgrade, independent chat lifetime, and share activity recreation.

## Validation record

| Item | Configuration / status |
| --- | --- |
| Development host | Windows; bundled JDK 21.0.12.1; Gradle 8.13; Android compile/target SDK 36 |
| Test device | Existing PocketMuseSmoke Android 16 emulator, x86_64; debug APK includes arm64 and x86_64 |
| Inference | Not required or invoked by capture; no model benchmark performed |
| Unit tests / build / lint | Debug APK and instrumentation APK built; 56 JVM tests passed; lint completed with 0 errors and 60 warnings; whitespace check passed |
| Instrumentation | 23 selected emulator tests passed, 0 failed/skipped: Inbox persistence (including 30 source fixtures and content URI retention), share recreation, navigation/manual capture/deletion, and legacy usability persistence |
| Visual check | Empty Inbox rendered correctly at 1080×2400 on the emulator; screenshot in local `.tools/inbox-check.png` |
| Target physical phone | Not selected in this session; RAM, storage, battery, thermal, and latency baselines remain open |
| Optional computer worker | Not selected or implemented; does not block standalone capture |

Reproduce with JDK 21 on the executable path (this repository's wrapper calls `java` directly):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -Pemulator
.\gradlew.bat :app:connectedDebugAndroidTest -Pemulator '-Pandroid.testInstrumentationRunnerArguments.class=dev.pocketmuse.InboxPersistenceTest,dev.pocketmuse.InboxShareTest,dev.pocketmuse.NavigationTest,dev.pocketmuse.UsabilityPersistenceTest'
```

For physical-phone measurement, record the exact device/OS, available storage/RAM, model file/hash, context settings, and power mode. Run the fixed capture set offline, measuring time to durable confirmation for text, links, and small/large PDFs. Separately record cold/warm inference latency, extraction/tool success, peak memory, battery delta and thermal state over a fixed workload. Store distributions and failures, not just an average. Set product budgets after measuring the target phone; emulator timings are not substitutes.

## Remaining gates and next work

- CINA-001 is **in progress**: fixture corpus and workflow expectations exist; physical-device measurements, storage budget, and held-out evaluation remain open. Work-engine/delegation flags belong with those future implementations.
- CINA-002 is **implemented and emulator-validated**: version-7 upgrade, source lifetime, rollback, idempotency, originals, and deletion passed. CINA-003 is **implemented and emulator-validated**, with physical-phone share-provider/lifecycle coverage still open. These results do not complete the broader P1 interpretation/review gate.
- Next: CINA-004/005, evidence-linked interpretation and editable review cards. Add summaries, candidate facts, suggested filing/actions, and unresolved questions without granting source text execution authority.
- Chat/widget capture integration, source export/backup, global retention controls, PDF page-specific citations, OCR/voice, retrieval, background interpretation, work engine, and computer delegation remain future work. Current passage offsets refer to extracted text, not PDF page locations.
- A capture interrupted before its transaction commits is not reported as saved; the user may need to share it again if the temporary URI grant has expired. A real MediaStore content-URI test verifies that deleting the provider's original after capture does not affect the saved source. Forced-stop, full process-death, remote provider, and physical-phone share-sheet scenarios still need device coverage beyond activity recreation.

Validation note: the first emulator installation attempt reported insufficient storage during startup. Installation succeeded on retry after startup settled. The final test run passed. The test runner performs its normal app install/cleanup; database regression fixtures use isolated temporary databases, and UI tests remove their own synthetic captures. No production-device data was used.

The existing chat, notes, reminders, and scheduled task tools retain their prior behavior. The new Inbox currently provides the reliable receive/store/read/delete foundation for the planned personal agent experience.
