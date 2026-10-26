# Live web gathering evaluation

## Scope and method

**Conclusion:** these models do gather real web data, but none passed the factual-answer and follow-through checks in Cina's current Quick/default lookup path. The controlled fixture from the earlier change was insufficient to establish reliability. Increasing from 0.6B to 1.7B did not by itself resolve the reported behavior. Deep/thinking mode was not evaluated here.

Real GGUF inference on disposable Android 36.1 x86_64 emulators, using Cina's production runtime, DuckDuckGo search, public page reader, tool parser and repeated-call guard. Quick mode, thinking disabled, no Home inference, no model escalation, no injected search/page results, and no saved Brave key. Emulator times are diagnostic observations, not phone performance benchmarks.

Models:

- Qwen3 0.6B Q8_0, 639,446,688 bytes, SHA-256 `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`.
- Qwen3 0.6B Q4_K_M, 396,705,472 bytes, SHA-256 `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`. Downloaded from [Unsloth's Qwen3 0.6B conversion](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/tree/50968a4468ef4233ed78cd7c3de230dd1d61a56b). This is a different quantization of the same 0.6B model family, not an independent model family.
- Qwen3 1.7B Q8_0, 1,834,426,016 bytes, SHA-256 `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a`, added after both 0.6B variants produced factual mistakes.

Four scenarios in fresh chats. Both 0.6B variants completed two repetitions. The 1.7B comparison was stopped after one full pass because it already failed the factual-answer and hand-off criteria; it is not scored as an eight-case pass:

1. Search for the Canadian Museum of History's free-admission day and hours, including a source.
2. Read the supplied official museum page and give the actual free-admission day and hours.
3. Follow up with "can you please check the schedule for me?" after a previous request for the garbage schedule at the fictional **100 Example Avenue, Ottawa**, and a previous assistant hand-off.
4. Ask for a garbage collection day with no address or saved location.

The correct weekly museum answer is Thursday, 5–7 p.m.; special presentations are excluded. The official page also states that the summer Canada Strong Pass program has ended. General opening hours are not free-admission hours. [Official museum source](https://www.historymuseum.ca/plan-your-visit).

The fictional-address scenario cannot establish a real pickup day. It tests actual search execution, source follow-through, and honest handling of an unverified address. The missing-address scenario should ask for the needed location rather than invent a day. Automatic approval review rejected sending the residential address from the screenshot to search services; no live test used that address.

## What counts as evidence

`NativeWebGatheringTest` is an opt-in observation harness. Its JUnit success means records were collected, not that the model answered correctly. Every outcome records the exact executed tools, arguments, retrieved text, final answer or failure, duplicate-call recovery, and elapsed time. Final answers must be manually checked against the retrieved evidence and authoritative source. Merely finding a link, returning fluent prose, or matching one expected word is insufficient.

The harness allows web search, page reading and read-only tool/skill discovery. It refuses unrelated actions and does not execute writes. Production foreground tool errors stop the turn; the harness preserves that behavior. Two initial missing-address observations were excluded because the first harness version incorrectly stopped at harmless `local_find` discovery; the final runs allow it.

## Bugs exposed and fixed

The initial Q8 baseline searched and read real data, but both supplied-page repetitions exhausted context after reading the same page under a section fragment. Both schedule follow-ups mistyped a returned URL and received HTTP 404. Baseline search-only questions produced the correct weekly hours through duplicate-call recovery but omitted the requested URL.

Changes made before the final model comparison:

- Constrain `web_read` URLs to exact URLs supplied by the user or returned by successful web tools; preserve these choices across model recovery. Source text cannot add capabilities.
- Treat section fragments as the same page in duplicate-call keys.
- Stop repeating the full page/result payload when transitioning from the routing decision to the final answer; the engine already has that context.
- Remove redundant blank lines and image placeholder characters from extracted page text.

These fixes prevent specific execution failures. They do not verify a model's interpretation of facts or guarantee that it follows through. The live runs still exposed incorrect facts and generic hand-offs in 0.6B answers.

Validation for these changes: 104 JVM tests, 34 Android runtime regression tests, app/test APK builds, and Android lint passed. Existing lint warnings remain. Changes are local and have not been released or installed on the user's phone.

## Final observed results

Twenty completed scenarios are preserved in [the condensed evidence](live-web-results-2026-10-25.json). It contains intentionally uncorrected model answers, including false claims; use this review to interpret them. Raw fetched-page payloads remain in the local `.tools` evidence, with payload hashes in the condensed file. No fabricated tool results were used.

| Model | Completed scenarios | Actual searches / page reads | Repeated-call recovery | Outcome |
| --- | ---: | ---: | ---: | --- |
| 0.6B Q4_K_M | 8 | 8 / 2 | 8 | Retrieved data, but failed factual accuracy and follow-through |
| 0.6B Q8_0 | 8 | 7 / 5 | 8 | Retrieved data, but failed factual accuracy, follow-through and missing-detail handling |
| 1.7B Q8_0 | 4 | 3 / 4 | 1 | More direct completions, but still failed factual accuracy and follow-through |

Specific findings:

- **0.6B Q4:** both search prompts found the correct weekly free-admission window. One answer added incorrect general opening hours. Both supplied-page prompts produced false free-admission claims, including weekdays 10 a.m.–5 p.m. Both schedule follow-ups returned a generic direction to check the website. Missing-address controls asked vague clarification questions without inventing a pickup day.
- **0.6B Q8:** both search prompts returned the correct weekly free-admission window. Both supplied-page prompts failed: one presented an expired program as current and confused operating hours; the other claimed admission was always free. Both schedule follow-ups handed the lookup back to the user. One missing-address control gave a meaningless definition; the other invented collection on the first day of the month.
- **1.7B Q8:** the search prompt returned the correct weekly window, but omitted the requested URL. The supplied-page prompt directly read the correct page and cited it, yet reported Thursday's 9 a.m.–7 p.m. opening hours as free-admission hours. The schedule follow-up searched and read the city page but still sent the user to the address form, without clearly saying the address-specific day remained unverified. With no address or city supplied, it searched using location placeholders, read Toronto's site, and assumed Toronto before asking for more details.

The 1.7B process was deliberately stopped after the first complete four-case pass once these critical failures were recorded; its interrupted second pass is not scored. Its result is not a completed eight-case test-suite pass. No recommendation to switch automatically to 1.7B follows from these results.

The source-URL and context fixes removed the specific mechanical failures observed in the initial Q8 baseline: the final small-model comparison had no tool/runtime errors. That is distinct from answer correctness. All sixteen final 0.6B runs still needed repeated-call recovery. The models can obtain useful source data, but fluent completion and valid tool calls do not establish that their answers are grounded. A stronger or differently configured model and/or a factual verification stage must pass these same live checks before claiming this issue is solved.

This is a small diagnostic evaluation, not a broad model benchmark or a guarantee about other requests. It does not establish the user's real garbage schedule.

## Reproduction

Build the emulator app and test APKs with `:app:assembleDebug :app:assembleDebugAndroidTest -Pemulator`. Install on a disposable emulator, then copy one complete GGUF to `/sdcard/Android/data/dev.pocketmuse/files/`.

Run `dev.pocketmuse.NativeWebGatheringTest` through the Android instrumentation runner with `liveWeb=true`, `modelFile=<GGUF basename>`, `repeats=2`, and a unique `runLabel`. Optional `caseIds` selects comma-separated scenario IDs. Results are written as `<runLabel>-results.jsonl` and `<runLabel>-progress.txt` in the app's external files directory. Review actual facts and sources; do not score the runner's exit status as model accuracy.
