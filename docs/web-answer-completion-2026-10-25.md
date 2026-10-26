# Completing web lookups

Follow-up: [live 0.6B/1.7B evaluation](live-web-evaluation-2026-10-25.md) goes beyond the controlled fixtures below. Live testing exposed additional execution bugs and factual mistakes; a passing fixture does not establish live-answer reliability.

The reported reply directed the user to a municipal website instead of answering their question. Inspection found three concrete gaps in the current source: search returned snippets only, a successful simple lookup forced text-only completion immediately, and "can you please check the schedule for me?" did not retain the earlier required-search intent. This identifies code paths capable of producing the behavior; the screenshot alone does not reveal the installed version or actual tool trace.

The runtime now makes a structured next-step choice after web results. It may read a source, refine a search, perform another requested step, or answer from sufficient evidence. The one-search text-only shortcut has been removed. `web_read` provides a bounded unauthenticated HTTP(S) reader for public page text and links. Web permission is checked again at execution, and the tool is excluded from scheduled tasks. Redirects, bytes and text are bounded; scripts are removed from extracted HTML; unsupported formats and inaccessible pages report errors. Results remain untrusted data.

The shared prompt distinguishes a link to an answer from the answer itself. It requires concrete source-backed facts, further research when possible, and a specific explanation when blocked. Follow-up recognition preserves the original lookup intent without enabling Web, inventing missing details, or turning local-note searches into web searches. Existing call budgets, duplicate guards and write approvals still apply.

## Limits

This is a public text reader, not a browser automation engine. It cannot run JavaScript, log in, submit forms, or read PDFs. Some municipal schedules require an interactive address lookup; this change does not guarantee retrieval of those schedules. Small models can still make bad routing or factual decisions. The screenshot's real address and collection schedule have not been verified by these tests. Changes are local and have not been published or installed on the user's phone.

## Validation

Tests cover follow-up intent, Web-off and scheduled restrictions, page extraction and truncation, public URL/address checks, and scripted search → source read → concrete answer through the production dispatcher. The real-inference fixture now hides the day from search snippets, requiring a source read to obtain it; it uses a fictional address and day.

Completed checks: 101 JVM tests, 34 Android regression tests, Android lint (0 errors; existing warnings), and app/test APK builds. Qwen3 0.6B Q8_0 completed the source-reading fixture: it executed `web_search`, then `web_read`, then answered Tuesday from the page. It did not include a source URL in its final text, despite the prompt instruction; the source remains in the tool result. This is evidence of follow-through on the controlled task, not a guarantee of citation compliance or success on interactive websites.

Qwen3 1.7B Q8_0 also passed the same fixture, executing search and page read before answering Tuesday with the source URL. See [native decision and answer evidence](web-answer-native-results-2026-10-25.txt). The emulator initially ran out of storage during model transfer; after replacing the temporary model copy, Android evicted the test database from cache. The fixture now uses `noBackupFilesDir` and deletes its database afterward. The rerun passed. No user phone data was changed.
