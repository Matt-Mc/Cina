# Scheduled local plans and step recovery

Implemented October 14, 2026 as the next CINA-009 slice. Schema **11** extends durable tasks with ordered step receipts, a requested start, an approval start and an idempotency key for scheduled suggestions. No release was published.

## What is available

**Tasks → Actions → New local task**, or **Create task for review** from a kept Inbox action, now supports one to eight ordered note/reminder creation steps. Add, remove or reorder unexecuted steps. Choose **Schedule start** for one future run, or run after approval. The editor preserves drafts through configuration changes.

Review shows every remaining step, exact note content or reminder time, destination and chosen start. One approval authorizes this ordered plan for one run. Scheduled execution cannot start before the chosen instant. Its window ends 15 minutes after that start; immediate or already-overdue work has 15 minutes from fresh approval. The window bounds the entire remaining plan. Missing it requires approval again; it does not create a repeating grant or silently catch up. Future start selection is bounded to the next year. Reminder times must be after the plan's start.

Each step saves its result and receipt in its own transaction, with read-back verification before commit. A failed later step leaves earlier verified results intact. The next worker invocation continues at the first pending step. Completed steps are locked in the editor and cannot be removed, reordered or modified through the plan. Editing remaining steps, changing the start, pausing, cancelling or changing the source revokes approval. A partial plan can be reviewed and approved again without recreating its completed notes/reminders.

Task cards show per-step progress and verified result references, including partial results on paused or cancelled tasks. Task history records each local write. Notes and reminder notifications keep their previous semantics: completion proves the local record was saved, not that a notification appeared.

## Scheduling and compatibility

WorkManager uses a delayed job for the approved start. The recovery sweep recreates missing delayed jobs and processes due plans, bounded to eight steps per plan per worker pass and 50 plans per sweep. The coordinator rechecks the actual clock, source validity and the complete plan fingerprint inside each step transaction. Late execution, multiple workers and restarts do not repeat committed steps. Android can delay work, and force-stopped apps cannot resume background work until reopened.

Existing once/daily/weekly **Scheduled** model prompts continue running on their existing schedule. Their eligible note/reminder tool calls now become unapproved tasks in **Actions**, rather than directly creating local results. The prompt run stops at the first proposed write and saves a clear review-needed result. It does not pretend the proposed note or reminder was created. The user can expand that suggestion into a plan before approving it. Read-only scheduled tools retain their prior path. Unattended creation of additional prompt schedules is no longer exposed or executable.

Scheduled suggestions are deduplicated by the schedule execution scope and original tool call key, before resolving relative reminder times again. A retry with the same run identity returns the existing task without replacing user edits, decisions or receipts. New occurrences receive new review tasks. Legacy successful/uncertain tool receipts are checked first and are never replayed as new writes. Different generated requests or deliberately new manual runs can create distinct drafts; this does not authorize them.

This is not a migration of model inference into the step coordinator. Existing scheduled model runtime, timing/recurrence rules and chat goals remain separate. Local plans have one selected start, not recurring automatic execution. There is no output substitution between steps, branching, remote execution, external writes or automated planner. Every supported step has concrete content before approval.

## Migration, retention and recovery

Schema 10 tasks become single-step plans. Existing completed result IDs become completed step receipts. Queued version 10 approvals are revoked and returned to review because their old fingerprints did not cover the new plan contract; they are not silently expanded into plan grants. Other states and task IDs remain. Older schema upgrade fixtures exercise the complete migration chain.

Source deletion, library cleanup and library replacement retain the previous authority-revocation behavior. Completed outputs and independent task history remain. Library backups still exclude work, steps and approvals; their payload/envelope versions are unchanged. Finished-task purge/export remains a separate lifecycle item.

## Validation

Tests cover ordered resume after helper reopen, no early execution, missed-window review, expiry between steps, immutable completed prefixes, editing remaining steps, pause/cancel after partial completion, rollback of a later failed write, plan/time tampering, step limits, invalid reminder timing, schema 10 receipts and approval invalidation, scheduled-prompt draft deduplication, stable relative reminder resolution and legacy receipt preservation.

The real WorkManager test schedules a two-step plan six seconds ahead, checks that no note exists early, and checks both verified results after the start. The Inbox UI flow builds and approves two steps, recreates the activity before approval, leaves the Tasks screen and checks both worker-created notes. The opt-in two-process recovery fixture commits step one, force-stops/relaunches the app and checks that only step two is added; an unapproved task remains pending.

Validation passed: **63 JVM tests, 70 emulator regression tests, and both phases of the between-steps force-stop/relaunch exercise**. Debug and instrumentation APK builds succeeded; lint reported **0 errors / 62 existing warnings**; diff whitespace checks passed. The two-step approval screenshot was visually inspected. Evidence: `.tools/work-plans-final-tests.txt`, `.tools/plan-restart-prepare.txt`, `.tools/plan-restart-verify.txt`, and `.tools/plan-approval.png`. The initial UI test matched receipt labels in both the underlying task card and the history dialog; its assertion now targets the dialog.

Tests used the owned Android 16 x86_64 emulator without model inference. The scheduled-tool bridge was tested directly; generated-model quality across real recurring prompts is not demonstrated here. Physical-device date-picker/time-zone behavior, clock changes, reboot/Doze/battery delays, notification delivery and large task-history performance remain release checks. The existing native date/time picker resolves the selected time to an instant; explicit DST-overlap selection is not added.

Next: unified work presentation and meaningful follow-up (CINA-010), followed by the owned-computer adapter spike. Full P3 still requires chat-goal migration and the outstanding physical-device gates; this slice does not mark the full transformation complete.
