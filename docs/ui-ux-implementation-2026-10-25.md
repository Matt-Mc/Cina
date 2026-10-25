# Cina usability implementation

October 25, 2026. Implements the six priorities accepted from the [UI/UX review](ui-ux-review-2026-10-25.md), with related source-review and control improvements.

## Everyday use

| Area | Implemented behavior |
| --- | --- |
| Getting started | Chat's recommended-model action opens guided setup. It offers one popular text-model candidate within estimated phone limits, with download/storage information and an explicit browsing alternative. Optional phone/Home choices remain in model management. Completing setup returns to the preserved chat draft. |
| Work | The default view shows Needs you, due work, chronologically ordered Upcoming items, ongoing work, and recent results. Category editors sit behind filters. Rows open the selected task, reminder, goal, schedule, or Home job. Pending local actions open their exact approval without running them. Completed local steps link to the specific saved note or reminder. |
| Navigation | A return history replaces fixed parent guesses. The top arrow uses the same back dispatcher as Android Back, including nested details and dialogs. Sources return to the original search. Home pairing returns to its model choice. Reopening Work from the main menu returns to its overview. |
| Library | A search field, capture action, new-note action, and typed results replace the category-only landing page. Search covers note contents, personal details, projects, current source-backed facts, and source passages. Results open the selected record. Source/reference material remains separate from personal chat memory. |
| Chat | Conversation history lives in the title control. The companion is smaller and decorative. Auto/Quick/Deep and model management share one menu. Home work opens its job details, while location and sharing information remain visible. Dragging to earlier replies suspends automatic following; a latest/new-reply action returns to the end. |
| Writing and deletion | Switching task-step types retains note content and reminder time. Invalid steps have specific selectable error messages. Notes use a full-screen editor and a persistent local draft, with Resume and Discard controls and protection against replacing a draft while opening another note. Deleting a saved personal detail requires confirmation. |

## Related improvements

- Source suggestions lead with Save fact, Create task, or Use category. Edit and Later are secondary; review-only acceptance and dismissal remain available. Saving a task draft still requires a separate execution approval.
- Project filing is collapsed below suggestion review.
- Memory has one canonical editor, with a shortcut from About you.
- Captured-library storage/backup is in Settings, labeled to reflect its actual scope.
- Shared navigation, back, add, send, and stop controls use vector icons. Interactive parents carry their names and roles; decorative icon children are silent.
- Shared navigation rows use larger supporting labels and more compact spacing. Shared pages constrain reading width on larger windows.

## Data and execution

No schema migration, automatic knowledge promotion, new persistent permission, or execution-engine merger was introduced. Goals still run while their chat is open; scheduled phone work remains subject to Android scheduling; Home work retains its own lifecycle. Overview rows describe those distinctions.

Library search uses the existing bounded source/fact keyword retrieval alongside local notes, projects, and personal details. It does not claim to search chat transcripts. Choosing a result does not add it to model context.

A starting model is selected by popularity and estimated resource fit, not by a claim of measured answer quality or action reliability. The setup completion regression uses an installed-model fixture and does not download a multi-gigabyte model.

The new note draft is stored privately on the device. Captured-library backup remains limited to its existing scope; moving its entry point does not expand that backup to all app data.

## Verification

Verification uses a separate disposable Android 16 x86_64 emulator and a temporary application suffix for isolation from another active Android build. The suffix is applied by a local verification script; it is not part of the app's normal build configuration.

The automated checks cover cross-library search, both Back controls, exact approvals without execution, model-setup completion and retained chat drafts, note-draft recovery after activity recreation, protected memory deletion, step-type switching, and chat reading position. Existing navigation, model picker, source-project/knowledge, notification routing, and suggestion-review checks are also included.

Final results and actual app captures are recorded in the [preview gallery](../preview/uiux-2026-10-25/README.md).

Full TalkBack interaction, a physical Fold device, live account sign-in, actual Home inference, and phone model speed/battery measurements remain device/account checks. Dark appearance and an adaptive navigation rail were secondary review proposals and are not part of these six changes.
