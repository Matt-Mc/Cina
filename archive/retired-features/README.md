# Retired features

Autonomous goals, custom skills and the Cina Home companion were retired on 2026-10-31. The companion implementation, its deployment workflow, and removed Android components/tests are retained here for historical reference. This directory is not an Android source set and its workflows do not run.

The app keeps existing goal tables, skill preferences, Home credentials and job records untouched. It does not resume goals, load custom procedures, pair with Home, synchronize Home jobs, or send new content to the companion. Old WorkManager jobs terminate successfully without a network request. Existing server installations are not stopped or modified by this migration.

Use Settings → Model endpoint for direct Ollama/llama.cpp chat. Scheduled prompts and reminders remain supported on the phone.

The supported app surface keeps Chat, Library (Collections, Notes, Memory and existing captured material), Work (reminders, scheduled prompts and approved local actions), and Settings. Multi-step local action plans remain explicitly approved; they were not included in the user's retirement decision. The decorative avatar is separate from the retired Home service.
