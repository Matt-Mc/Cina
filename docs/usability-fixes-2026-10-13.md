# Cina audit fixes — October 13, 2026

The prioritized app-side changes from the [usability audit](usability-audit-2026-10-13.md) are implemented. Live model discovery and phone benchmarks are included alongside the audit fixes. This is a local implementation and debug build; it has not been published.

## What changed

| Audit finding | Implemented change |
| --- | --- |
| Scheduled tool descriptions disappear | One typed catalog supplies the complete background allowlist, and context preparation receives the actual scheduled request. |
| Follow-ups lose tools; discovery exhausts the action limit | Selection considers recent user requests and attached files. Discovery, reads, and writes have separate budgets, plus a total limit. |
| Long-note edits can discard unread content | Added listing and paged reads, append and partial edits, optimistic version checks, and a complete-read requirement before replacing a body. |
| Approvals hide content or use technical dates | Full editable fields, nested connected-service values, readable local times, date pickers, resolved item titles, and an expandable current-note preview. Persistent permission is separate and off by default. |
| Failed requests and approvals are lost | SQLite stores turns, pending approvals, execution receipts, and note-read coverage. The last opened chat is restored. Failed/interrupted requests offer Retry and Edit. Goals retain their execution scope across pauses. |
| Poor contrast and clipped large text | Darker secondary text, growing headers, scrollable navigation/welcome content, and labels for repeated controls. |
| First-run/settings overload | Compact mode menu in chat; dedicated Responses settings. Empty installations show one starting model card and no benchmark panel. Existing model discovery remains available. |
| Chat/file lifecycle gaps | Search, rename, archive, delete, and Markdown export for chats; extracted-text inspection/removal for files; Notes export. Chat deletion removes its derived data and offers a separate choice for saved memories. |
| Goal and schedule workarounds | New goal opens a goal form. Schedules can be edited, reassigned to another model, run immediately, or retried. Manual runs keep the regular schedule. Running, waiting, paused, failed, and completed states are distinguished. |
| Unpredictable memory | Extraction asks for a short fact and supporting quote. Every incidental fact requires review. The app handles retention dates; users choose whether a suggestion replaces an existing fact. Policy text stays visible in Memory. |
| Misleading connection setup | Browser-ready sign-in is separated from advanced token/server setup. Connection progress/errors stay with the service, forms remain open on failure, and complete remote schemas are supplied. |

Notification permission is requested in the reminder/schedule flow instead of at startup. A declined permission produces a clear notice and Enable action. Switching web access off is also checked at execution time, including within an existing turn.

## Phone model comparison

Models can benchmark all installed models in sequence, show progress, stop a run, and retain each completed result. The results expose generation speed and the existing three task checks, with suggested Quick and Deep assignments. Individual benchmarks remain available in model details. Discovery now loads popular text GGUF repositories from Hugging Face, excludes split GGUF files, and checks candidate sizes against this phone’s resources. Starting picks use size and popularity, not measured reasoning or action reliability.

## Simpler instructions and stronger action boundaries

`ToolCatalog` now owns tool names, field types, required values, labels, concise instructions, and scheduled availability. The base instruction has seven short rules. Only one matching procedure is loaded initially; discovery remains available. Trusted response preferences are separate from source data. Older-chat summaries use token-aware excerpts.

The native sampler applies a schema-derived grammar when an action begins. A malformed interactive action gets at most one forced-format repair, capped at 30 seconds; validation and approval still apply afterward. Text-only recovery remains separate. Native processing errors propagate to persistent retry controls, and native resources are released on error recovery.

An exact completed write returns its stored receipt instead of executing again. An interrupted write with an unknown outcome is blocked and asks the user to inspect the saved item or service. A retry with prior actions requires review before new writes. This provides exact-call protection, not semantic deduplication of differently worded actions or proof of the outcome of a remote timeout.

## Verification

- 42 JVM tests passed, including scheduled catalogs, short follow-ups, attachment availability, separate budgets, local date resolution, ambiguous times, and daylight-saving transitions.
- 27 Android instrumentation tests passed, covering navigation, editable approvals with long/nested content, migration with retained data, memory review, long-note protection, stale versions, repeat/uncertain writes, restored approvals and goal scope, chat deletion, manual schedules, and execution-time web settings.
- The native repair sampler was exercised with an existing local GGUF, including return to ordinary text generation after reset.
- Android lint completed with no errors and 57 warnings. Remaining warnings include existing dependency, style, and trust-manager findings; this work is not a security certification.
- Debug builds compile for the x86_64 emulator and ARM64 phones.
- Visual checks covered normal and 200% text, the chat composer with the keyboard open, navigation/settings at enlarged text, and reminders with notifications disabled. Captures and the final instrumentation output are in [audit-fix-evidence](audit-fix-evidence/).

## Validation still needed

The small local GGUF used for the native smoke test sometimes answered in plain text instead of requesting an action. Forced-format repair passed, but grammar cannot make a model choose an action or understand the task. The model-specific phone evaluation in the original audit is still necessary. Starting model cards use size and popularity; they are not a certification of reliable tool use. The existing three-question benchmark is also not a complete capability evaluation.

No Fold 7 speed, battery, thermal, or reliability measurements are claimed. Real account sign-in and connected-service writes were not exercised. TalkBack focus behavior and a full landscape/device-size matrix still require device testing. Conversation history remains a bounded transcript in context; native multi-message history/template migration is a separate architectural follow-up.

The database migration is additive (version 7). Existing chats, notes, memories, reminders, and schedules are retained. The phone APK is a debug build for review, not a signed distribution release.
