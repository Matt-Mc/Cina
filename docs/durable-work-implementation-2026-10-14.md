# Durable local actions: CINA-008 / initial CINA-009

Implemented October 14, 2026. Database schema **10** adds durable tasks and task events. Existing chats, library data, goals, reminders and schedules are retained. No release has been published.

## Available workflow

1. Keep an Inbox **action** suggestion, then choose **Create task for review**. Alternatively open **Tasks → Actions → New local task**.
2. Choose **Note** or **Reminder**, edit the exact title/content, and explicitly select any reminder date/time. Cina does not infer ambiguous dates for execution.
3. Save the task. It waits for approval across activity recreation and app restarts; no note/reminder is created yet.
4. In **Tasks → Actions**, choose **Review action**. The dialog shows the destination and exact arguments. **Approve once** grants one execution within 15 minutes; **Deny action** cancels it. Dismissing the dialog leaves it unapproved.
5. Android WorkManager runs the action independently of chat. Completion follows reading back the saved note/reminder. Finished tasks expose history and navigation to Notes or Reminders.

Editing revokes approval. Pause revokes approval; Resume returns to fresh review. Cancellation cannot undo an already committed local save: a competing completion changes the task revision, so a stale cancellation is rejected. Completed tasks never rerun because a result is subsequently edited or deleted. Repeated promotion of the same Inbox proposal returns its existing task instead of creating another action.

This is a bounded first coordinator: **one local create action per task**. Legacy chat goals and scheduled model prompts retain their current execution paths. Multi-step plans, schedule migration, automatic replanning, external writes, computer delegation and standing grants are not implemented here. The existing Goals tab describes its chat-bound behavior; the new Actions tab describes background work.

## Authorization and persistence

`WorkCoordinator` owns save, approval, pause/resume/cancellation, source checks, local execution, read-back verification and receipt/event persistence. It accepts only local note/reminder creation. It does not call the model, general tool dispatcher, network or connected services. Chat YOLO/standing permissions are never consulted.

An approval binds the action type and all arguments with a length-delimited SHA-256 fingerprint, has a 15-minute wall-clock expiry, and is cleared on edits, pause, cancellation or completion. Optimistic task revisions reject stale dialogs. Source-linked execution also requires the original proposal ID/revision to remain an accepted action. Proposal updates revoke authority and move pending tasks to Needs review. The user must keep the latest source suggestion and edit/save the task before approving again.

For these two SQLite-local actions, eligibility checking, side effect, read-back verification, result ID and completion events share one SQLite transaction. Running and verifying are audit events within that transaction, not separately committed long-lived states. A crash/failure before commit leaves the task queued and no result; after commit it leaves the completed task and one result. Multiple helpers/workers serialize on the transaction and cannot repeat a completed action. The current time is sampled after the transaction lock is acquired. This guarantee does **not** extend to future external providers; those will need their own idempotency and reconciliation contract.

The scheduler persists a recovery sweep before accepting UI approval, then enqueues a uniquely named worker after approval commit. A crash between commit and enqueue is recovered by the sweep or app reopen. The periodic sweep runs at Android's minimum 15-minute interval; Android may delay it. It never extends expired approval. Boot/package-update hooks and app resume also request recovery. Force-stopping the app prevents Android background execution until it is opened again. No network or notification permission is needed to save a note/reminder.

Reminder alarm registration occurs after the database commit using the existing idempotent alarm identity and recovery path. A retry registers the existing reminder rather than creating another. Completion proves **saved reminder**, not alarm firing or notification delivery. Notification permission, device timing and battery restrictions still apply. On storage/worker failures, WorkManager retries; expired authority returns the task to approval. There is no separate user-facing failure taxonomy or multi-step retry policy yet.

## Source lifecycle and backup interaction

Source deletion, retention cleanup and library replacement cancel pending linked tasks and remove their live source/proposal pointers. Completed notes/reminders and task details/history remain independent records. UI deletion/restore text discloses this retention. A backup does not contain work, approvals, tasks or their results; restoring sources cannot import authority. Standalone previously approved tasks retain their scope. Library backup envelope/payload remain version 1 because the authoritative library tables are unchanged.

Task data and history currently have no purge/export UI. General library retention is not claimed to erase these independent task records. Extending retention to finished-task records must preserve a receipt/tombstone policy before enabling automatic replay or recreation.

## Validation

Automated checks cover policy allowlisting and full argument binding; pending approvals and queued tasks across reopened database helpers; denial, pause/resume, stale revisions, expiry and direct argument tampering; source edits/deletion; backup restore revocation; repeated promotion/execution; competing executors; rollback when completion recording fails; reminder read-back and deleted-result behavior; and schema 9→10 migration. The existing older migration, capture, knowledge and backup regressions run as well.

The Compose flow goes from a kept Inbox action through task editing and activity recreation, verifies the exact approval dialog, leaves Tasks for Chat after approval, and checks that the real WorkManager worker saved exactly one note. A separate opt-in two-process fixture prepares approved and unapproved tasks, force-stops/relaunches the app, then verifies recovery without executing the unapproved task.

Validation passed: **63 JVM tests, 58 emulator regression tests, and both phases of the force-stop/relaunch recovery exercise**. Application and instrumentation APK builds succeeded; lint reported **0 errors / 62 existing warnings**; diff whitespace checks passed. The approval dialog screenshot was visually inspected. Evidence: `.tools/durable-work-final-tests.txt`, `.tools/durable-work-restart-prepare.txt`, `.tools/durable-work-restart-verify.txt`, and `.tools/work-approval.png`. An initial UI test screenshot selected both the activity and dialog roots; targeting the dialog fixed the test capture.

Testing used the owned Android 16 x86_64 emulator without a model. Physical-device reboot, low-storage/power-loss testing, notification delivery under battery restrictions, and long-lived workload performance remain release checks. The SQL failure injection demonstrates transactional rollback, not a physical power-loss test.

Next: expand CINA-009 into scheduled-work adaptation and richer recovery, then CINA-010's unified work presentation. Full P3 gates remain open until legacy goals/schedules and additional work stages use the coordinator.
