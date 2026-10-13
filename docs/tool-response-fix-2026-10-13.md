# Tool requests appearing as final replies

The two reported screenshots contain attempted calls, not execution results:

- `[create_reminder]title: Bring a jacket at 11:30, when_text: "11:30 AM"[/create_reminder]`
- `[web_search]{"name":"search","arguments":{"query":"stores around ottawa that sell riftbound"}}[/web_search]`

Cina's executable protocol is `[tool]{"name":"...","arguments":{...}}[/tool]`. Previously, the native lazy grammar, parser and display filter only recognized that opening. Alternate tags bypassed the grammar, returned no request from the parser, and were saved as ordinary final answers. Consequently the app never reached execution or its existing result-to-answer continuation.

`ToolResponseProtocol` now recognizes leading known tool names and tool/tool_call wrappers (including partial streamed prefixes). Only the canonical envelope is accepted for execution. Other recognized forms enter the existing single, time-limited repair attempt, then normal argument validation, permissions, execution and result handling. Chat and scheduled work share this repair path. A failed repair stops with an error rather than saving tool syntax as a successful answer. Ordinary prose, inline examples and Markdown links remain text.

A second issue affected continuation after repair: the temporary system instruction demanded another action and remained active. Repair now restores the assistant instructions, thinking setting, normal action grammar and bounded prior results before returning the repaired request.

## Validation

- 46 JVM tests passed, including both screenshot strings, every recognized streaming prefix, canonical envelope checks, and ordinary text/link handling.
- Three deterministic Android instrumentation tests passed on the disposable emulator. They cover repair followed by real local note execution and a final answer, preservation of approval requirements, the search screenshot, a one-repair limit, failed repair, and invalid arguments.
- App and Android test builds succeeded for ARM64 and x86_64.

The Android recovery tests use scripted inference output with the real runtime, parser, storage and local tools. They do not measure Qwen3-1.7B repair accuracy, actual web search, or reminder delivery on the user's phone. This is a local code fix, not a published update. Detection intentionally applies to leading recognized tags; arbitrary prose or novel model protocols are not converted into actions.
