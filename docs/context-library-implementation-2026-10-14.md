# Reviewed knowledge, projects, and retrieval — October 14, 2026

This implements the core CINA-006 slice of the [personal agent roadmap](cina-personal-agent-roadmap.md), following [interpretation and review](inbox-review-implementation-2026-10-14.md). The changes are local; no release has been published. The broader P2 gate remains open.

## Available flow

- Open **Inbox → Knowledge & projects** for Find, Knowledge, and Projects.
- Create or rename a project, then link/unlink a source from its Inbox detail. A source can belong to several projects. Removing a project removes its membership/name history, while preserving sources and knowledge.
- Keep a fact suggestion, then explicitly choose **Save as knowledge**. A confirmation shows the exact wording and lets you choose no expiry, seven days, or thirty days. Merely keeping a suggestion still does not promote it.
- In Knowledge, correct wording, explain the revision, change validity, or mark a fact Current, Review, or Archive. History retains old wording, status, validity, reason, and time; the UI shows wording/status/reason/time. Original source evidence remains available for comparison.
- Find searches source titles, extracted passages, shared captions, and current reviewed knowledge. Use literal keywords, with up to eight words/300 characters; all words must match within the indexed record. Filter by project or restrict results to current knowledge. Results are bounded to thirty, put current knowledge first, distinguish raw source passages, and link back to the source.
- Knowledge and source records can be deleted independently. Source deletion also removes its derived knowledge, history, index records, and project links. Deleting only a fact preserves its source and reviewed suggestion; recreating it requires another explicit save.

The library works offline without a model. It provides evidence retrieval, not generated answers. It is not automatically inserted into chat prompts and does not modify the legacy personal-memory system.

## Persistence and correction rules

Schema version 9 adds `context_projects`, `project_sources`, `project_history`, `knowledge`, `knowledge_history`, and an FTS4 `context_search` index with the Unicode tokenizer. The upgrade indexes existing version-8 captures and preserves their original bytes and review decisions. It does not automatically promote old facts or turn category labels into projects.

Knowledge keeps its originating source, proposal, proposal revision, and exact evidence. Promotion checks that the current proposal is still an accepted fact, rejects credentials, and is idempotent by proposal ID. Repeated saves cannot overwrite a later human correction or validity choice.

Knowledge revisions and search-index changes commit together. Current retrieval uses only the latest active fact, with expired, archived, and unresolved records excluded. Historical wording remains in history, not in the current-knowledge index. Changing or withdrawing the originating suggestion marks its non-archived knowledge as needing review and removes it from current search; users explicitly review/reactivate it in the library.

Raw original sources are deliberately preserved, so a general search can still find an old claim in original evidence after a correction. Such results are labeled **Original source passage/caption**; use **Current knowledge only** to exclude them. There is no generated answer that silently treats those old claims as current facts.

SQLite triggers maintain the source/knowledge indexes and deletion cleanup. Source text is immutable; changing a source requires a new capture. Project links and knowledge inherit source lifetime. Revision checks reject stale project renames and stale fact edits. Project rename history is stored; project merge/unmerge and a project-history UI are not included in this slice.

## Validation record

- Debug app/test builds and JVM tests run with JDK 21 and Gradle 8.13. Two new search-policy tests cover Unicode, literal operators, and bounded query size.
- Android 16 emulator tests cover version-8 backfill and version-7/version-5 upgrades; explicit/idempotent promotion; correction persistence after reopening; stale edits; validity cutoffs; archive/conflict exclusion; cross-source project retrieval; unlink/removal; source/knowledge deletion; and hostile search syntax.
- The UI test creates a project, links a source, promotes a reviewed fact, corrects it, searches the new wording, and opens the original source. It uses synthetic source/proposal fixtures and does not call a model.
- One older migration test initially expected version 7. Data preservation succeeded; its expected current version was updated to 9. The first emulator install attempt also reported insufficient storage during startup; a later install succeeded without clearing app data.
- Final validation: **58 JVM tests and 40 emulator tests passed**; debug app and instrumentation APKs built; lint reported **0 errors and 62 warnings**; whitespace checks passed. The end-to-end library test was rerun after the small deleted-project filter fallback change and passed again.
- The rendered corrected-knowledge search result was visually inspected at 1080×2400. Local evidence is retained in `.tools/context-final-tests.txt` and `.tools/context-search.png`. The screenshot demonstrates the corrected fact, its current-knowledge label, and a source link; test data is synthetic and removed by the test cleanup.

Reproduce with JDK 21 on the executable path:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -Pemulator
.\gradlew.bat :app:connectedDebugAndroidTest -Pemulator '-Pandroid.testInstrumentationRunnerArguments.class=dev.pocketmuse.ContextRepositoryTest,dev.pocketmuse.ContextLibraryFlowTest,dev.pocketmuse.InboxPersistenceTest,dev.pocketmuse.InboxInterpretationTest,dev.pocketmuse.IntakeReviewCardTest,dev.pocketmuse.NavigationTest,dev.pocketmuse.UsabilityPersistenceTest,dev.pocketmuse.MemoryPersistenceTest'
```

## Remaining work

The immediate next package is CINA-007: retention/storage controls, encrypted export, and verified restore. Current storage remains the existing private app database with Android backup disabled; logical deletion is not a forensic secure erase.

P2 still includes model-assisted answers across retrieved sources with citations, a held-out retrieval evaluation, automatic conflict detection, people and legacy-note/work links, project merge/unmerge, and backup/recovery gates. This slice provides literal keyword retrieval and manual conflict review, with a deterministic current-knowledge lifecycle. It does not claim semantic search, complete long-document coverage, or comprehensive personal memory integration. Results have a fixed limit rather than pagination or relevance ranking; physical-phone and large-library performance still need measurement.
