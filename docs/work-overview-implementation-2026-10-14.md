# Work overview, follow-ups and small-model controls

Package: CINA-010, initial implementation. Release A device/model gates remain open.

## Delivered

- Tasks opens a work overview grouping attention, upcoming work, progress and results across durable actions, reminders, scheduled prompts and chat goals. Category links retain the existing editors and full action review.
- Local action completion, approval requests and changed-source input requests produce follow-up notifications. Unchanged sweeps stay quiet. Permission or channel denial leaves the state visible in Tasks and eligible for later notification delivery. A notification opens Actions and cannot approve a plan.
- Six model tools: `list_work`, `read_work`, `draft_work_step`, `append_work_step`, `schedule_work`, `manage_work`. Drafting is incremental: append one note/reminder using the returned plan ID and revision. Separate append and schedule tools require those identifiers. Manage can pause, cancel or resume. Resume and edits require fresh user approval.
- Draft mutation and retry receipts commit in one SQLite transaction. Old revisions fail; retries reuse their original result. Model-facing bodies are bounded previews, with explicit truncation flags. Full content remains available in Actions.
- A request can start one plan. The app retains its identity during continuation, removes new-plan creation from the next generation schema, and directs remaining steps to append. A persisted origin key rejects accidental second plans even across retries/restarts. Creating separate plans requires separate requests. Approval still occurs in Actions.
- Explicit local-plan requests receive work/discovery tools relevant to the requested operations and the corresponding constrained schema. Runtime parsing rejects substitutions of direct-write tools for these requests. Reminder/start times must come from the user's request or recent user context. There is no model approval command.

No schema migration is needed: database version remains 11. Notification delivery signatures use app-private preferences. Library backup continues to exclude tasks and execution receipts. The durable action executor supports local notes and reminders; legacy chat goals and scheduled inference remain separate execution paths, surfaced together in the overview.

## Model evaluation

The opt-in Android tests use the real native runtime, production prompt/tool selection, grammar and parser. Eight fixed cases exercise creating a note draft, appending a step, scheduling, pausing, reading, drafting a reminder, asking for a missing time, and explaining the approval boundary. Correct tool name and expected fields are required; malformed calls and unsolicited actions are failures. The continuation test separately checks reusing a returned ID, producing exactly two ordered steps and stopping before execution.

The initial interface/corpus scored **3/8** with Qwen3 0.6B Q8_0, both before and after restricting it to work/discovery tools. Qwen3 1.7B Q8_0 also scored **3/8** on that initial one-response corpus and failed the two-step continuation by creating a second plan instead of appending. These findings drove the separate append/schedule tools and narrower operation menus. The revised v2 corpus quotes exact note/reminder text, permits two read continuations before acting, and validates matched calls against transactional storage. Scores across the two corpus versions are not directly comparable.

The model sometimes invented details; runtime or executor validation rejected invalid inputs. No approval grants or note/reminder side effects were created during these tests. Use the structured Actions UI for exact control, or a model that passes the intended workflow evaluation. Sub-1B autonomous plan control is a quality gate rather than a supported blanket claim.

Qwen3 0.6B Q8_0 control result: **4/8 v2 cases**. Note drafting, explicit note append, reading and approval-boundary explanation passed. Scheduling, pausing, reminder drafting and missing-time clarification failed. Before app-owned continuation, the separate two-step test also failed: it attempted to create a second plan instead of appending to the returned plan ID. Conditional argument schemas prevented note drafts from carrying reminder times when the model used the supported tool format; malformed alternative wrappers still failed closed. Evidence: `.tools/work-model-06b-final.txt`.

Qwen3 1.7B Q8_0 control result: **7/8 v2 cases**. All requested controls and the approval-boundary explanation passed. The model invented a missing reminder time; runtime validation rejected it with `ToolDetailsRequiredException`. The existing chat handler displays that exception as a focused date/time question, so the app safely handles this model failure without saving or executing the invented reminder. The score intentionally records the model's failure rather than counting the safeguard as model competence. Evidence: `.tools/work-model-17b-final.txt`.

After adding app-owned continuation, the 0.6B model still failed the two-step test: it saved the first step, then prematurely claimed both were ready. Storage contained only one unapproved step. This is a remaining model-completion/truthfulness limitation; the stored Actions view is the authoritative result. Evidence: `.tools/work-cycle-06b-controller.txt`. The continuation retest quotes exact text for both steps; earlier continuation failures used unquoted text, so those runs are diagnostic rather than a controlled quality comparison.

With the same app-owned continuation, **Qwen3 1.7B passed the two-step integration test**: it reused the returned plan identity, saved exactly the requested two ordered note steps, stopped, and left both unapproved with no note/reminder execution. The run completed in 51.829 seconds on the emulator. Evidence: `.tools/work-cycle-17b-controller.txt` and `.tools/work-cycle-17b-controller-run.txt`. This supports bounded small-model operation with application-managed state; it does not establish reliable sub-1B free-form autonomy.

The corpus is a smoke test, not a broad quality benchmark. Single runs do not establish success rates across phrasing, context length, quantization, models or physical phones. No 3B model has been measured in this slice.

## Application validation

- **64 JVM tests passed**; debug app and instrumentation APKs built successfully.
- **94 Android regression tests passed**, including work tools, duplicate-plan prevention, real notification navigation, approval UI, scheduling and recovery, migrations, capture/review, library backup and retrieval, and existing tool-protocol behavior.
- Lint: **0 errors, 64 warnings** (62 pre-existing warnings and two style suggestions for deliberately synchronous notification-signature persistence).
- Overview screenshot inspected. A missing notification-route allowlist entry was fixed after the initial UI test exposed it. The notification test also restores its launch intent for ActivityScenario teardown after verifying the real pending intent.
- An older scheduled-tool test was updated to the already-adopted approval-only behavior: scheduled model writes save an unapproved suggestion, not a note. Unsupported scheduled actions remain rejected.
- Final regression evidence: `.tools/work-overview-final-tests.txt`. Model evidence is stored separately, so passing application regressions do not conceal model capability failures.

## Reproduction

Measured GGUF SHA-256 values:

- Qwen3-0.6B-Q8_0.gguf: `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031` (639,446,688 bytes).
- Qwen3-1.7B-Q8_0.gguf: `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a` (1,834,426,016 bytes).

Environment: PocketMuseSmoke Android 16 x86_64 emulator, 6 GiB RAM, local CPU inference; JDK 21 / Gradle 8.13. These timings are not phone performance measurements.

Build with the repository JDK/SDK using `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -Pemulator --offline`.

Install debug and instrumentation APKs on the Android emulator. Normal regressions include `WorkToolsTest`, `WorkOverviewTest`, `ToolResponseRecoveryTest`, the existing work-plan/approval suites and capture/library/navigation suites.

For native tests, copy one complete GGUF at a time into the app's external files directory, then run instrumentation with `-e work_model <basename>` and class `dev.pocketmuse.NativeWorkControlTest` or `dev.pocketmuse.NativeWorkPlanCycleTest`. Missing opt-in arguments skip the tests; a requested missing model fails. Text evidence is written to `work-model-<basename>.txt` and `work-cycle-<basename>.txt`. Native fixtures use files storage, because Android can evict cache-backed databases during low-storage model tests.

## Remaining release work

Physical-device battery/background timing and notification-permission checks; broader model/paraphrase evaluations; concise verified chat follow-ups; migration of legacy goal execution; optional owned-computer inference/delegation. Sub-1B free-form plan control remains an explicit quality gate, not an assumption based on parameter count.
