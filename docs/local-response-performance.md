# Local response modes, measurements, and memory

Cina stays local. Auto is the default; Quick and Deep are manual overrides in chat and Settings. Auto shows the resolved mode and local model used for the last response. Settings allows a local model assignment for each mode, or reuse of the currently selected model. A deleted assignment falls back to the selected model.

Auto uses a deterministic policy without a classifier generation: tracked goals, longer requests, and analysis/comparison/research requests choose Deep; ordinary requests choose Quick. This is a starting policy, not a guarantee about task difficulty. A manual selection takes precedence. Quick requests up to 512 output tokens and Deep up to 1,536, reduced if the remaining context cannot fit them. Model templates that support thinking use the native `enable_thinking` setting. Quick and text recovery disable thinking; Deep enables it. Unsupported templates retain their model behavior and Quick displays a notice.

The runtime resets the conversation cache while keeping the selected model weights resident. A different model or another inference owner still requires loading. Native generation now honors the requested output limit; the previous calculation inadvertently added the input token count to that limit. Recovery remains one bounded text-only attempt, with no repeated tool execution.

## Measurements

Settings → Response timings retains the last 30 runs on the phone. Each record includes the local model display name, resolved mode, outcome, prompt token count, recovery count, and elapsed milestones from Send: inference access, model ready, context ready, first output, first answer text, generation finished, answer ready, memory finished, summary finished, title finished, and total. User prompt processing, tool duration, and observed thinking-block time are durations rather than milestones. Thinking time is summed across attempts; untagged model reasoning cannot be identified. No request, reply, or model path is recorded. Clear removes these samples.

The answer becomes available before housekeeping. Housekeeping runs under the shared inference lock and is canceled when a new request arrives. Native loading and prompt processing can still take time to yield; inference access timing helps expose that delay. A sample may omit milestones when work is interrupted or unnecessary. Total includes housekeeping and is not the time users must wait before sending another message.

## Context

Only matching tool groups and up to two matching skill procedures are loaded initially. Compact discovery entries let the model find another local tool or enabled procedure. Existing action permissions are unchanged. MCP continues to discover remote tool schemas separately.

Budgets use the actual native context capacity, capped by the model's trained context and the runtime's 8,192-token maximum. Profile, memory, summary, and skill text receive bounded allocations; recent history uses the remaining budget and preserves contiguous recent messages. Historical messages include their source dates. Oversized requests receive an explicit size error rather than native silent truncation. Tool-heavy turns can still exhaust the context and require a shorter request or a new chat.

## Memory

When memory is enabled, each user message is queued in SQLite before inference. Jobs survive interruption and restart. The app resumes pending processing when reopened with a selected model and after completed turns. No OS background worker is added; processing requires the app's inference runtime. Each batch processes at most three messages with a 25-second extraction limit per message. Failed jobs retry on later turns; after three failures, Memory displays a Retry action. Cancellation does not consume a retry.

Extraction requests explicitly stated facts, their kind, a verbatim supporting quote, sensitivity, a relevance cutoff, and any conflicting saved fact. Ordinary candidates with matching evidence save automatically with Undo/Keep in chat. Sensitive, uncertain, or conflicting candidates become proposals for review. Credential-like details are excluded. Per-message phrases such as “don't remember” and “do not save” skip extraction. Classification depends on model quality; matching evidence and conservative text checks reduce errors but do not guarantee perfect sensitivity detection or fact extraction.

Temporary plans retain the source date. “Next week,” “next month,” and “tomorrow” use source-date-based relevance cutoffs. Other temporary facts use a supplied cutoff or a 30-day retention fallback, capped at a year; these cutoffs are not asserted appointment dates. Expired facts remain visible for review but are excluded from saved-memory retrieval. Dates in older conversation history remain available as historical context.

Approved corrections replace the indicated old fact in a database transaction. Users can also edit or delete saved facts directly. Undo applies to the pending group of automatically saved details, including details saved just before an interrupted batch. Disabling memory cancels extraction and blocks memory tools. Existing explicit `save_memory` calls continue through action approval.

## Fold 7 validation protocol

Use the same installed model, settings, and prompts for comparison. Run each request three times after a cold load and three times with resident weights. Test greeting/arithmetic, the Lisbon packing question, a short writing request, an itinerary comparison, a tracked goal, and a local note/reminder action. Run Auto, Quick, and Deep. Record first answer and answer-ready medians separately from total time, and compare recovery rates and answer quality. Repeat with both assigned models if using two.

Check a Lisbon plan in a new chat, after a model switch, and after app restart. Check Undo, sensitive review, conflicting-fact approval, memory disabled, malformed extraction retry, and expired-plan retrieval. Interrupt housekeeping with a new message and confirm the new request takes priority.

Build and emulator checks verify wiring and persistence. No Fold 7 timings or speedup figures are claimed until this protocol is run on that phone.

The instrumentation dependency uses Espresso 3.7.0 for its Android 16 InputManager fix, documented in the [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0). UI tests grant notification permission before launch so the first-run system dialog does not cover the app.
