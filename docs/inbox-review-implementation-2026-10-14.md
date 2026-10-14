# Inbox interpretation and review — October 14, 2026

This continues the [capture implementation](inbox-implementation-2026-10-14.md) with CINA-004 and CINA-005 from the [personal agent roadmap](cina-personal-agent-roadmap.md). These changes are local and have not been published.

## User flow

1. Open a saved Inbox item and choose **Interpret with local model**. Cina uses the downloaded model selected in Settings. Capture and reading still work without a model.
2. Review summaries, category suggestions, candidate facts, possible actions, and questions. Each suggestion has **View source quote**, with an exact retained quote and its source/caption offsets.
3. **Edit** changes wording and returns the suggestion to pending review. **Keep**, **Defer**, and **Discard** persist independently for each card. A kept category appears on the item in the Inbox.
4. Keeping an action or fact records a review decision only. It does not create a task, reminder, note, or memory, and does not send anything. Execution and knowledge promotion remain later work.

The source remains immutable. User edits are marked and retain the original supporting quote. An exact quotation establishes provenance, not factual correctness or semantic entailment; reviewers must still assess the suggestion.

## Implementation contract

- Interpretation has no tool dispatcher, external connections, chat history, or personal memory. The captured material is presented as untrusted data. Selecting a model does not authorize source instructions.
- Model output must match a bounded JSON structure, use known suggestion kinds and passage IDs, and quote 4–400 exact characters from a supplied passage. Each suggestion is capped at 600 characters, with at most eight per response. Unknown fields, invalid types, missing evidence, unsupported actions, and malformed output fail visibly before persistence.
- The database revalidates source offsets and quotes before accepting a batch. All proposals save in one transaction. A failed batch leaves no partial review.
- The existing version-8 proposal table is now used; no additional schema migration is required. Payloads retain model name, policy version, analyzed range, edited flag, and content. Evidence stores source field, UTF-16 start/end offsets, and exact quotation.
- Interpretation uses the same process-wide model lock as chat and scheduled work. It has bounded waiting/generation time, explicit stop, and local model cleanup. It does not run automatically after capture.
- Each run considers up to eight 1,200-character passages, further restricted by the model's context. Source text precedes accompanying captions. Cards disclose the analyzed range and that remaining content was not analyzed. This is an excerpt interpretation, not complete long-document processing or retrieval.
- Optimistic revisions prevent stale edits and stale generation results from overwriting current review decisions. Once a source has suggestions, it is not automatically reprocessed. Deferred/discarded cards remain available for later editing/review. Empty results may be retried.
- Deleting a source removes its proposals. An in-flight generation cannot recreate a deleted source. Review failures persist on the intake item; no durable `running` state can leave a source stuck after process death.
- Accepted category text is organization metadata for this Inbox item, not creation of a project or movement of the original file. The first accepted category is shown in the list when a model suggests multiple categories.

## Validation

- Debug app and instrumentation APKs built successfully; 56 JVM tests passed.
- Thirty selected emulator tests passed: evidence parsing, whole-batch rollback, editable decisions, stale revisions, repeat processing, source deletion during processing, excerpt limits, cancellation, caption offsets, Compose review-card controls, and the prior capture/navigation/persistence regressions.
- Lint passed with zero errors and 62 warnings, including two additional advisory suggestions to use the SQLite KTX transaction helper; transaction code follows the existing repository style.
- The seven interpretation/review tests were rerun after strengthening database-reopen assertions and adding a card screenshot; all passed. The edited card was visually inspected (`.tools/intake-review-card.png`).
- The final direct instrumentation run passed all eight selected tests (the seven interpretation/review checks plus one native smoke test). Together with the earlier regression run, 31 distinct emulator test cases passed. Controlled-response tests exercise boundaries and persistence; they do not establish real model interpretation accuracy.

### Real model observations

Qwen3-1.7B Q8_0 ran through the production runtime on the Android 16 x86_64 emulator with 6 GB RAM. The final run produced supported summary/fact suggestions for a preference in 10,813 ms, and an action plus an unresolved-time fact for a draft request in 10,284 ms. The latter preserved ambiguity but did not phrase the missing time as a question. The hostile quoted-instruction case failed exact-quote validation and saved no interpretation. That case is recorded as a safe rejection, not a successful interpretation. All cases left notes, reminders, and personal memory unchanged.

The first 2.5 GB emulator attempt was manually stopped under memory pressure. The first 6 GB run exposed a prompt-format ambiguity: the model copied a union of type names and omitted the JSON wrapper. The prompt now uses a concrete example with allowed kinds listed separately; validation remains strict. A follow-up run accepted two ordinary sources but rejected the hostile source. The native test was then updated to assert that this specific rejection leaves no proposals, while still requiring successful interpretation of both ordinary sources. The final eight-test run passed with that explicit contract.

These timings are emulator observations, not physical-phone targets. Local raw evidence is in `.tools/native-inbox-results.txt` and `.tools/inbox-review-final-tests.txt`. The default model may still produce invalid output or weak suggestions; the larger fixture/held-out quality gate remains open. The model test does not establish broad prompt-injection resistance.

Reproduce the regression set with JDK 21 on the executable path:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -Pemulator
.\gradlew.bat :app:connectedDebugAndroidTest -Pemulator '-Pandroid.testInstrumentationRunnerArguments.class=dev.pocketmuse.InboxInterpretationTest,dev.pocketmuse.IntakeReviewCardTest,dev.pocketmuse.InboxPersistenceTest,dev.pocketmuse.InboxShareTest,dev.pocketmuse.NavigationTest,dev.pocketmuse.UsabilityPersistenceTest'
```

The optional `NativeInboxInterpretationTest` requires `Qwen3-1.7B-Q8_0.gguf` in the app's external files directory and records `native-inbox-results.txt` there. It checks three short synthetic sources through the production local runtime; it is not a held-out quality evaluation.

## Status and next gates

CINA-004/005 now have implemented, emulator-tested interpretation and review paths. Broader groundedness/quality evaluation on the 30-source fixture corpus, physical-device latency/battery/thermal measurements, and lifecycle stress tests remain open. These are required before calling all of P1 complete.

Next development work is personal context and retrieval: promote reviewed knowledge with provenance and lifecycle controls, organize sources into projects, and retrieve relevant passages. Unified task execution and optional computer delegation remain separate later phases. No silent conversion of kept facts/actions into those systems is permitted.
