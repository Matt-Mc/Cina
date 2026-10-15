# CINA-007: library retention and encrypted recovery

Implemented October 14, 2026. Schema remains version 9; portable backup envelope and library payload both start at version 1. No release was published.

## User flow and scope

Open **Inbox → Knowledge & projects → Storage & backup**. The screen reports source, knowledge and project counts and original-file bytes. Original-file bytes are not total database/disk usage.

- Prepare a backup using a confirmed passphrase of 12–1024 characters, then choose a destination with Android's document picker. A selected cloud document provider can upload the encrypted file; Cina itself adds no upload or network operation.
- Open a backup, enter its passphrase, and validate it before seeing the restore confirmation. Restore replaces the complete library, including removal of current records absent from the backup. It does not merge. Export the current library first to retain it.
- Preview sources captured more than 30, 90 or 365 days ago. Confirmation lists every affected source and the linked fact count. Cleanup removes originals, passages, suggestions, knowledge/history, project membership and search entries. Project definitions remain. Recently reviewed facts still disappear if their original source meets the capture-age rule.
- Retention defaults to keeping everything. Cleanup is manual; no automatic background deletion is scheduled.

The backup includes ten authoritative tables: sources, original chunks, passages, intake items, proposals, projects, project membership/history, knowledge and knowledge history. Derived search indexes are rebuilt by insertion triggers. Chats, legacy notes/memories, jobs, model files, settings, account tokens and connection secrets are excluded. Sensitive text intentionally captured in an Inbox source remains part of that source and is included. Restore never dispatches actions or tools.

## Format and recovery controls

The envelope uses AES-256-GCM with a fresh 16-byte salt, fresh 12-byte nonce, 128-bit authentication tag, and PBKDF2-HMAC-SHA256 at 600,000 iterations. The fixed version header, salt and nonce are authenticated as associated data. Unknown versions, wrong passphrases, truncation and authentication failures are rejected before parsing the payload. There is no compression or archive path extraction.

The binary payload has a version, fixed table order, row counts, typed values and length-prefixed UTF-8/blob fields. The importer supplies table and column names from the known schema; the file cannot supply SQL or filenames. Limits are 32 MiB payload, 100,000 total rows, and 1 MiB per variable field, plus the existing per-source limits. Export validates its own snapshot before offering the file picker. In-memory staging checks schema constraints, references, original-file SHA-256 hashes, ordered passages, evidence ranges/quotes, knowledge provenance and current history consistency. Search shadow tables are never imported.

Restore repeats validation inside a database transaction and commits only after the entire replacement succeeds. A hash of the authoritative library at preview time is checked inside that transaction: any intervening edit/capture/deletion requires a new preview. Cleanup uses the same stale-preview check. An injected insertion failure demonstrates rollback, including search results. These checks use a consistent snapshot; no live SQLite database file is swapped while other helpers are open.

The passphrase is not stored in preferences, saved-instance state, logs or backup metadata. Mutable password/key buffers are cleared after use; decrypted preview buffers are cleared on completion, cancellation or ViewModel disposal. Managed strings, crypto-provider copies and memory reclamation are not claimed to provide guaranteed secure erasure. Prepared encrypted backups and validated previews survive configuration changes in a ViewModel, but process death requires starting again. No plaintext export file is intentionally created.

The live SQLite database remains app-private under Android device protection; this change does not introduce SQLCipher or an app-level database key. Users must retain their backup passphrase separately: there is no recovery key or reset. Deleting local records neither deletes older exported files nor guarantees forensic erasure from flash/journals. Restoring an older backup can intentionally bring previously deleted records back.

## Validation and limits

Automated coverage includes fresh encryption randomness, wrong password, header/salt/nonce/ciphertext tampering, truncation and oversized input; original/review/corrected knowledge/history/project/search recovery; deletion propagation; stale restore and cleanup previews; invalid references, evidence, hashes and payload versions; trailing data; empty replacement; and transaction rollback on insertion failure. A Compose flow enters a passphrase, encrypts, writes and reads a local file through ContentResolver, confirms restore, and previews/confirms cleanup. Library navigation is covered by the existing project/knowledge flow.

Validation: **61 JVM tests and 47 emulator tests passed**, debug application and instrumentation APKs built, lint reported **0 errors / 62 existing warnings**, and diff whitespace checks passed. The navigation flow passed again after the final Inbox refresh adjustment. The storage screenshot was visually inspected. Test logs are in `.tools/backup-final-tests.txt` and `.tools/backup-navigation-final.txt`; the screenshot is `.tools/storage-backup.png`. The initial broader run exposed an existing UI test's assumption that its source button fit without scrolling; it now scrolls explicitly.

Android tests ran on the owned Android 16 x86_64 emulator, without a model or network. Document-provider picker interoperability, physical-device performance, peak memory near the 32 MiB limit, disk-full and OS process-kill testing remain release checks. The UI flow uses a file URI to exercise ContentResolver streams without depending on a particular document provider. A failed provider write may leave an incomplete encrypted file; no successful-backup message is shown on an observed write failure. Provider durability is not verified by reading the destination back.

This is a bounded library backup, not a complete phone/app backup or an independent cryptographic audit. Broader P2 answer-generation and organization gates remain open. The next implementation package is CINA-008: a shared work coordinator with durable steps and explicit approval states, before Inbox action suggestions can execute.
