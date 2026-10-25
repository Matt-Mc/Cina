# Tool routing and unfinished replies

## Failure

Phone chat previously used a lazy tool grammar: it constrained a tool call only after the model emitted `[tool]`. The model could instead finish with ordinary prose such as “Let me check the collection schedule.” That text satisfied the final-answer check, so the saved turn completed without executing a search. There was no background job to deliver another message.

## Change

The first model output in an interactive phone turn or scheduled prompt is now a required structured decision. The grammar allows the selected tools plus an internal `reply_now` choice. Tool choices enter the existing parser, validation, approval, execution, and result-continuation path. `reply_now` is handled only by the runtime and is never an executable tool or permission grant. It generates a final text answer, clarification, or explanation of a limitation.

For clear lookup intent, the app requires `web_search` as the only valid first decision when Web is available. This includes explicit search/look-up requests and collection-schedule questions with a street address. A short “are you checking?” follow-up can retain that earlier intent. Requests to search local notes/files/memory, writing requests, missing lookup subjects, and instructions against searching retain the normal routing choice. This rule does not enable disabled Web access or scheduled network work.

This is a routing step before work, not a retry after a stalled answer. Its output is hidden from chat. A lookup instruction explicitly requires the supplied city and street address in the query. The existing capability filter still removes Web when disabled and limits scheduled work to supported tools.

After a tool runs, its confirmed result goes through the existing continuation flow. The extra routing pass is not repeated after each result. This keeps multi-step actions available and avoids encouraging small models to select an already completed lookup again. Existing duplicate-call limits and receipt/approval checks remain in place.

The subsequent [model routing and escalation change](model-routing-and-escalation-2026-10-25.md) finishes simple successful web lookups with a text-only answer. Complex research and requests with further actions retain the tool continuation. That change also adds native support for explicitly disabling tool formatting, which is used for direct answers and lookup completion.

A reply that claims a search or action is still in progress cannot complete the turn successfully. This check also applies after tool results and during the existing text-only empty-answer recovery. Requested writing drafts are exempt so a user can ask for text containing “I'll check.” A stopped turn reports the failure; confirmed results remain available through the existing tool-result preservation path.

## Boundaries

This adds one short decision generation before a direct text answer. A tool decision goes straight to execution. Outside the clear lookup cases, the local model still chooses the tool; it always supplies the arguments. Structured output does not guarantee complete search results. Web search currently returns result snippets, not interactive municipal address lookups, so Cina must explain when those results cannot verify an exact collection day. This change does not add unattended background execution to ordinary phone chats.

## Validation

- `TurnDecisionTest`: capability-filtered routing schema, strict internal reply decoding, screenshot wording, normal answers, clarification, limitations, and requested drafts.
- `WebLookupPolicyTest`: explicit web intent, address-specific collection questions, checking follow-ups, missing details, local reads, writing and note/reminder requests, and instructions against searching.
- `ToolResponseRecoveryTest`: initial routing to real search execution with controlled network results; source results passed into the answer; disabled Web; writes still requiring approval; no successful progress-only replies before or after search; existing protocol and recovery cases.
- `NativeSearchRoutingTest`: the collection-schedule scenario through real Qwen inference, production parsing/tools/continuation, controlled fictional results, and the production duplicate-call guard. The test requires local GGUF fixtures and records decisions, executed tools, and final answers.

All lookup fixtures use fictional addresses and pickup dates. They do not verify the collection schedule from the user's screenshot.

Validation completed: 88 JVM tests, 23 Android instrumentation tests, Android lint, debug app and test-package builds. The native scenario passed on both Qwen3 0.6B Q8_0 and Qwen3 1.7B Q8_0. Both issued `web_search` with the supplied city and street address and returned the collection day from the controlled result. The 0.6B model attempted the same search again; the existing duplicate-call guard blocked execution and the text-only completion returned the confirmed day. The 1.7B model answered directly after the search and cited the fixture URL. See [native execution evidence](tool-routing-native-results-2026-10-25.txt).

Verification used a disposable emulator and a separate `.uxreview` application ID. These changes have not been published or installed on the user's phone.
