# Tool execution and continuation reliability

This follow-up supersedes the narrow format-repair fix in PR #14. The new screenshot shows bare function calls, not tool results:

```text
web_search("stores around Ottawa that sell riftbound")
create_reminder(title: "Jacket", when_text: "10 minutes")
```

## Root causes and changes

- The parser recognized tagged calls but treated bare function calls as final answers. A bounded literal reader now normalizes known function, JSON, tagged, and standalone fenced formats directly into the same validated request. It never evaluates model-generated code. The catalog teaches the canonical JSON envelope instead of advertising function signatures.
- The reminder parser required the word `in`. Numeric relative durations such as `10 minutes` now resolve correctly. Missing, ambiguous, past, and invalid times still fail validation.
- Real Qwen testing also produced an invented `tomorrow at 10 AM` for a request without a time. At the model boundary, reminder/schedule time fields must be copied from the current or two most recent user requests. A missing or unmatched phrase prompts for a date and time before approval or execution. System dates and tool results cannot supply this evidence. This conservative check can ask for clarification when a model reformats an otherwise valid time; manual approval edits still use the date picker and normal time validation.
- Relative times become absolute timestamps before approval. The original call identity now survives normalization, unchanged approvals, saved pending actions, and app restarts. Original and resolved keys share transactional receipts, so retries return a saved outcome or an uncertain-outcome warning rather than creating another reminder. User edits receive a new identity.
- Qwen's thinking template rewrites earlier reasoning when a new user/tool-result message arrives. Subtracting the old rendered transcript's length corrupted Quick continuations and crashed Deep continuations with `Chat template prefix changed`. Thinking-template inputs now render the complete conversation and rebuild the model cache. Completed assistant messages are stored without re-rendering them. Native prompt/generation exceptions become application errors; decoding failure is no longer mistaken for normal completion. Context and output limits remain bounded instead of silently dropping tool-result history.
- A subsequent native run reproduced Android's `HeapTaskDaemon ... SuspendAll timeout` abort inside prompt decoding. The long JNI inference calls incorrectly used `FastNative`, preventing garbage-collector suspension. They now use ordinary JNI, consistent with [Android's JNI guidance](https://developer.android.com/ndk/guides/jni-tips#fast_critical_native). The native regression forces garbage collection throughout inference to exercise this failure path.
- Literal calls do not need another inference pass. Only the old unquoted named-tag format is eligible for one bounded repair, restricted to the identified tool and with required field names present. Invalid literal arguments, duplicate keys, missing times, expressions, unknown tools, conflicting envelopes, and multiple calls do not enter repair.
- A repeated call after a successful result triggers the existing single text-only recovery. It cannot execute more tools. If continuation or recovery fails, chat and scheduled results retain confirmed successful tool output and report failure separately. Partial scheduled results remain visible alongside their error.

## Regression coverage

- JVM matrix: every one of the 24 catalog tools in eight literal formats (192 cases within one test), exact screenshot strings, nested objects/arrays, Unicode and escapes, streaming prefixes, malformed/multiple/unknown calls, ordinary prose, and relative-time validation.
- Android integration: scripted model output through the production runtime, parser, validation, SQLite storage, and tool dispatch. Covers screenshot calls followed by answers, repair restoration and limits, all catalog field types, approval persistence, completed/uncertain receipt replay, web disabled/provider failures, scheduled allowlists, and recovery that attempts another tool.
- Opt-in native integration: the official [Qwen3-1.7B Q8_0 GGUF](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF) generates actions and follow-up answers. Four Quick scenarios (search, reminder, create note, find note), the same four with historical raw tool syntax, two Deep scenarios (search and reminder), a missing-time check, and a separate forced-grammar/reset test. Web results in this suite are fixtures; reminder and note storage is real.
- Opt-in live search: the exact Ottawa/Riftbound query runs through the real HTTP provider and requires returned source URLs.
- Pull requests now run JVM tests, Android emulator regressions, native builds, and lint. Real-model and live-network tests are opt-in so ordinary CI does not depend on a 1.8 GB download or a public search provider.

## Reproducing checks

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -Pemulator
./gradlew :app:connectedDebugAndroidTest -Pemulator
```

For the optional model checks, copy `Qwen3-1.7B-Q8_0.gguf` to the debug app's external-files directory and run:

```sh
adb shell am instrument -w \
  -e class dev.pocketmuse.NativeToolCycleTest,dev.pocketmuse.NativeToolGrammarTest \
  -e nativeModel Qwen3-1.7B-Q8_0.gguf \
  dev.pocketmuse.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.pocketmuse.ToolLiveSearchTest -e liveWeb true \
  dev.pocketmuse.test/androidx.test.runner.AndroidJUnitRunner
```

Inspect the instrumentation result, not just the `adb` exit status: an Android process crash can still return shell exit code zero. Native cycle and live-search evidence are written into the test app's external-files directory.

## Limits

Cache rebuilding can increase prompt-processing time on thinking-capable models; it preserves the template's actual conversation rather than relying on a false append-only assumption. Local testing uses an Android emulator, not the user's handset. It does not certify notification delivery under every manufacturer's battery policy, external MCP services, Android editor completion, or every GGUF/chat template. Novel protocols and mixed prose/call responses are not automatically executed. Provider failures and uncertain write outcomes remain explicit failures; they are never reported as successful actions.
