#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>
#include <regex>
#include "json-schema-to-grammar.h"

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static bool g_enable_thinking = true;
static bool g_thinking_supported = false;
static bool g_return_thinking_prefix = false;
static std::string g_action_grammar;
static bool g_force_action = false;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configureToolSchema(JNIEnv *env, jobject, jstring schema, jboolean force) {
    const auto *raw = env->GetStringUTFChars(schema, nullptr);
    if (!raw) return false;
    const std::string value(raw);
    env->ReleaseStringUTFChars(schema, raw);
    if (value.empty()) {
        g_action_grammar.clear();
        g_force_action = false;
        return true;
    }
    try {
        const auto grammar = json_schema_to_grammar(common_json::parse(value));
        g_action_grammar = "root ::= \"[tool]\" action-root \"[/tool]\"\n" +
            std::regex_replace(grammar, std::regex("\\broot\\b"), "action-root");
        g_force_action = force;
        return true;
    } catch (const std::exception &error) {
        g_action_grammar.clear();
        LOGe("Action grammar failed: %s", error.what());
        return false;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configureThinking(JNIEnv *, jobject, jboolean enabled) {
    g_thinking_supported = false;
    try {
        if (g_chat_templates && common_chat_templates_support_enable_thinking(g_chat_templates.get())) {
            common_chat_templates_inputs probe;
            common_chat_msg message;
            message.role = "user"; message.content = "Hello";
            probe.messages.push_back(message);
            probe.enable_thinking = true;
            const auto thinking_prompt = common_chat_templates_apply(g_chat_templates.get(), probe).prompt;
            probe.enable_thinking = false;
            g_thinking_supported = thinking_prompt != common_chat_templates_apply(g_chat_templates.get(), probe).prompt;
        }
    } catch (const std::exception &) {
        LOGw("Thinking control is unavailable for this template");
    }
    g_enable_thinking = enabled;
    return g_thinking_supported;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_tokenizeCount(JNIEnv *env, jobject, jstring text) {
    if (!g_context) return -1;
    const auto *value = env->GetStringUTFChars(text, nullptr);
    if (!value) return -1;
    const auto tokens = common_tokenize(g_context, value, false, true);
    env->ReleaseStringUTFChars(text, value);
    return static_cast<jint>(tokens.size());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeContextCapacity(JNIEnv *, jobject) {
    return g_context ? static_cast<jint>(llama_n_ctx(g_context)) : 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = trained_context_size > 0 ? std::min(n_ctx, trained_context_size) : n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    if (!g_action_grammar.empty()) {
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, g_action_grammar);
        sparams.grammar_lazy = !g_force_action;
        sparams.grammar_triggers.push_back({COMMON_GRAMMAR_TRIGGER_TYPE_WORD, "[tool]"});
    }
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_thinking_supported = false;
    g_enable_thinking = true;
    g_action_grammar.clear();
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    current_position = 0;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    std::string formatted;
    if (g_thinking_supported) {
        common_chat_templates_inputs inputs;
        inputs.enable_thinking = g_enable_thinking;
        inputs.messages = chat_msgs;
        inputs.messages.push_back(new_msg);
        inputs.add_generation_prompt = role == ROLE_USER;
        // Thinking templates may rewrite earlier assistant messages (Qwen drops old reasoning
        // after a new user message). Their rendered history is not an append-only prefix.
        // Return the complete transcript and rebuild its KV cache before the next generation.
        formatted = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
    } else {
        formatted = common_chat_format_single(
                g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    }
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;
static bool generation_pending = false;

static void finish_assistant_message() {
    if (!generation_pending) return;
    common_chat_msg message;
    message.role = ROLE_ASSISTANT;
    message.content = assistant_ss.str();
    chat_msgs.push_back(std::move(message));
    generation_pending = false;
}

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    generation_pending = false;
    g_return_thinking_prefix = false;
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        if (start_pos + i + cur_batch_size > static_cast<int>(llama_n_ctx(context)) - OVERFLOW_HEADROOM)
            return 3;

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    try {
        // Reset long-term & short-term states
        g_action_grammar.clear();
        reset_long_term_states();
        reset_short_term_states();

        // Obtain system prompt from JEnv
        const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
        if (!system_prompt) return 1;
        LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
        std::string formatted_system_prompt(system_prompt);
        env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

        // Format system prompt if applicable
        const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
        if (has_chat_template) {
            formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, formatted_system_prompt);
        }

        // Tokenize system prompt
        const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                                   has_chat_template, has_chat_template);
        for (auto id: system_tokens) {
            LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
        }

        // Handle context overflow
        const int max_batch_size = static_cast<int>(llama_n_ctx(g_context)) - OVERFLOW_HEADROOM;
        if ((int) system_tokens.size() > max_batch_size) {
            LOGe("%s: System prompt too long for context! %d tokens, max: %d",
                 __func__, (int) system_tokens.size(), max_batch_size);
            return 1;
        }

        // Decode system tokens in batches
        if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
            LOGe("%s: llama_decode() failed!", __func__);
            return 2;
        }

        // Update position
        current_position = (int) system_tokens.size();
        return 0;
    } catch (const std::exception &error) {
        LOGe("System prompt processing failed: %s", error.what());
        return 4;
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    try {
        // Reset short-term states
        finish_assistant_message();
        reset_short_term_states();
        auto *next_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
        if (!next_sampler) throw std::runtime_error("Could not create the model sampler");
        common_sampler_free(g_sampler);
        g_sampler = next_sampler;

        // Obtain and tokenize user prompt
        const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
        if (!user_prompt) return 1;
        LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
        std::string formatted_user_prompt(user_prompt);
        env->ReleaseStringUTFChars(juser_prompt, user_prompt);

        // Format user prompt if applicable
        const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
        if (has_chat_template) {
            formatted_user_prompt = chat_add_and_format(ROLE_USER, formatted_user_prompt);
        }
        const auto opening_think = formatted_user_prompt.rfind("<think>");
        const auto closing_think = formatted_user_prompt.rfind("</think>");
        g_return_thinking_prefix = has_chat_template && opening_think != std::string::npos &&
            formatted_user_prompt.find_first_not_of(" \t\r\n", opening_think + 7) == std::string::npos &&
            (closing_think == std::string::npos || closing_think < opening_think);

        // Decode formatted user prompts
        auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
        for (auto id: user_tokens) {
            LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
        }

        // Reject overflow rather than dropping conversation or tool-result tokens.
        const int user_prompt_size = (int) user_tokens.size();
        const int max_batch_size = static_cast<int>(llama_n_ctx(g_context)) - OVERFLOW_HEADROOM;
        const bool rebuild_history = has_chat_template && g_thinking_supported;
        if (user_prompt_size + (rebuild_history ? 0 : current_position) >= max_batch_size) return 3;
        if (rebuild_history) {
            llama_memory_clear(llama_get_memory(g_context), false);
            current_position = 0;
        }

        // Decode user tokens in batches
        if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
            LOGe("%s: llama_decode() failed!", __func__);
            return 2;
        }

        // Update position
        current_position += user_prompt_size;
        stop_generation_position = std::min(current_position + n_predict, max_batch_size);
        generation_pending = true;
        return 0;
    } catch (const std::exception &error) {
        LOGe("User prompt processing failed: %s", error.what());
        return 4;
    }
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    try {
        // Stop if reaching the marked position
        if (current_position >= stop_generation_position) {
            LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
            finish_assistant_message();
            return nullptr;
        }

        // Sample next token
        const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
        common_sampler_accept(g_sampler, new_token_id, true);

        // Populate the batch with new token, then decode
        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token_id, current_position, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            throw std::runtime_error("Model decoding failed while generating a response");
        }

        // Update position
        current_position++;

        // Stop if next token is EOG
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
            LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
            finish_assistant_message();
            return nullptr;
        }

        // If not EOG, convert to text
        auto new_token_chars = common_token_to_piece(g_context, new_token_id);
        cached_token_chars += new_token_chars;

        // Create and return a valid UTF-8 Java string
        jstring result = nullptr;
        if (is_valid_utf8(cached_token_chars.c_str())) {
            if (g_return_thinking_prefix) {
                cached_token_chars.insert(0, "<think>");
                g_return_thinking_prefix = false;
            }
            result = env->NewStringUTF(cached_token_chars.c_str());
            LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

            assistant_ss << cached_token_chars;
            cached_token_chars.clear();
        } else {
            LOGv("id: %d,\tappend to cache", new_token_id);
            result = env->NewStringUTF("");
        }
        return result;
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    if (g_context) reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
    g_sampler = nullptr;
    g_batch = {};
    g_context = nullptr;
    g_model = nullptr;
    g_action_grammar.clear();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
