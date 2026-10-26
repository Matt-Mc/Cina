# Phone model evaluation shortlist

These are candidates for evaluation, not validated Cina defaults. None has yet passed the live web evaluation described in [the evaluation report](live-web-evaluation-2026-10-25.md). This list does not change model selection or download any weights.

| Suggested test order | Official model | Size | Reason to include |
| --- | --- | --- | --- |
| 1 | [Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B) | 4B | General assistant, reasoning, agent tasks, and image support. |
| 2 | [Gemma 4 E4B IT](https://huggingface.co/google/gemma-4-E4B-it) | 4.5B effective; about 8B including embeddings | Reasoning and native tool calling; evaluate its larger storage footprint separately. |
| 3 | [Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507) | 4B | Non-thinking instruction-following and tool-use comparison. |
| 4 | [Phi-4-mini-instruct](https://huggingface.co/microsoft/Phi-4-mini-instruct) | 3.8B | Microsoft model with function-calling support. |
| 5 | [SmolLM3-3B](https://huggingface.co/HuggingFaceTB/SmolLM3-3B) | 3B | Smaller Hugging Face model with tool calling and reasoning modes. |

Use comparable 4-bit builds where practical, each model's supported conversation format, and record thinking mode separately. Verify loading and tool integration before measuring accuracy. Evaluate correct concrete answers, sufficient search/page reading, missing-detail handling, response time, peak memory, and repeated tool calls. A successful tool call or green observation-harness result does not establish answer correctness.

Gemma's [official Q4_0 GGUF](https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/tree/main) is approximately 5.15 GB, exceeding the ordinary phone slot's current 2.5 GB download ceiling. Use the heavy-phone category if the device's limit permits it. Google's smaller mobile memory estimates apply to LiteRT-LM, not Cina's current llama.cpp runtime. Model weight size is not total running memory.
