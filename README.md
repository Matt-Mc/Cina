# Cina

Cina is a text-first Android assistant that runs GGUF language models on the phone. It uses the official llama.cpp Android binding, local SQLite storage, and Kotlin/Jetpack Compose. Inference, conversations, notes, reminders, and saved memory do not use a server.

## Build

Requirements: Android SDK platform 36, NDK 29.0.13113456, CMake 3.31.6, JDK 21, and an arm64-v8a Android 10+ phone. `vendor/llama.cpp` is pinned as a submodule at upstream commit `66e665c4276ee46f3ec9872dd7e5a496842bc44f`. Clone with `git clone --recurse-submodules`, or run `git submodule update --init` after cloning.

Run `gradlew.bat :app:assembleDebug`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## APK releases and updates

Each push to `main` runs [the APK workflow](.github/workflows/android-apk.yml). It builds an arm64 release APK, stores it as a workflow artifact, and publishes it as the latest GitHub Release. The build number increases on every workflow run. Cina checks the latest release when opened, prompts when its build number is newer, and opens the APK download when you choose **Download**. You can also check from **Settings**. If the phone blocks installation, allow APK installs from the browser or file manager you used to open the download.

Before the first release, create and safely back up a dedicated Android signing keystore. Add these four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `CINA_KEYSTORE_BASE64` | Base64 encoding of the complete keystore file (no line breaks) |
| `CINA_KEY_ALIAS` | Alias of the signing key in that keystore |
| `CINA_KEYSTORE_PASSWORD` | Keystore password |
| `CINA_KEY_PASSWORD` | Key password |

On Windows, you can encode the keystore with `[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\cina-release.jks'))` in PowerShell. Keep the keystore and passwords backed up securely: future APKs need the same key to install over existing releases. The workflow stops before building if any signing secret is missing. APKs made by `assembleDebug` have a different signing key and cannot update to these release APKs without first uninstalling the debug app; uninstalling deletes its local app data. Install the first signed release to receive in-app update prompts for later builds.

## First run

1. Open **Models** and download a recommended model, search a Hugging Face GGUF repository, paste a direct HTTPS GGUF URL, or import a local GGUF file.
2. Select an installed model. Chat works without network access after the model is installed.
3. In a chat, enable **Web** only if you want internet search. Set your own Brave Search API key in **Settings**. Gated Hugging Face files require account approval and a token entered in Settings.
4. Notes and reminders are stored inside Cina. Calendar, alarm, and share actions open Android system apps for the final user step.
5. To use remote tools, open **Settings → Connected tools**, choose a service, and enter an access token with the permissions you want Cina to have. Cina checks the connection and loads the server's tool list. Disconnecting deletes its saved token.

## Remote MCP tools

Cina includes ten hosted MCP presets: Gmail, Google Drive, Google Docs, Google Sheets, Google Slides, Google Calendar, Google Chat, Google Contacts (People API), GitHub, and Linear. Google Workspace MCP requires a configured Google Cloud project and OAuth authorization; a pasted Google access token expires, so renew it in Settings when needed. GitHub accepts a personal access token and Linear accepts an API key or OAuth access token. Presets are connection options, not accounts connected by default.

Connected servers receive only the arguments of tools Cina calls. Tokens are encrypted with Android Keystore, never included in model prompts or action logs, and removed on disconnect. Cina discovers tool names and input schemas from the server, can search them during a chat, and asks for approval before each remote tool call unless that chat has YOLO mode enabled. Remote server responses are treated as untrusted data. The MCP client supports HTTPS Streamable HTTP with the 2026-07-28 protocol and a 2025-03-26 fallback; it does not run local `stdio` MCP servers or perform an interactive OAuth sign-in yet.

The assistant requests confirmation before creating or sharing data. Per-chat YOLO mode skips these confirmations for the app's available tools and displays an action log. It turns off when the app leaves the foreground or the user changes chats. Chat is the home screen; Models, Notes, Memory, and Settings live in the side menu. The You profile stores optional personal details for future chats; companion appearance and response preferences are in Settings.

## Current boundaries

- GGUF text chat models only. Import validation checks GGUF structure; llama.cpp decides whether an architecture can run on this device.
- Reminder notifications are inexact and depend on Android notification permission and battery policy.
- Web search sends its query to Brave. Search results are treated as untrusted input to the local model.
- No cloud inference, voice, accessibility automation, or background autonomous agent. Connected MCP services do exchange data with their providers when used.
- Device performance and memory use vary by model. The app gives an estimate before download; test on target phones before distribution.
