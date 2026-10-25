# Cina UI/UX review

October 25, 2026 · Current working tree, including changes beyond `11c6660`.

Implementation update: the six accepted priorities are covered in the [implementation notes](ui-ux-implementation-2026-10-25.md), with a current preview gallery and verification results.

## Overall recommendation

Keep Cina's warm paper background, green accent, serif identity, readable replies, and companion. The main usability problem is the number of concepts people must understand before getting a result.

Organize everyday use around three intentions: **ask Cina, save something, follow up**. Choose sensible defaults, show the next action, and reveal configuration when someone needs it. Preserve the distinction between capturing information, accepting knowledge, approving an action, and completing work.

This is a review and proposed direction. No application behavior was changed.

## Evidence and limits

Reviewed the current Compose implementation for Chat, navigation, Library, Inbox, source review, Knowledge & projects, Notes, Memory, Work, task editing, Models, Settings, connections, profile, and approvals. Visually inspected eight existing captures, including the newer [Library](../preview/consolidation-2026-10-21/library.png) and [model choices](../preview/models-2026-10-24/model-choices-connected.png).

Some gallery images predate the current implementation. Findings below use current source for behavior; older images illustrate layout only. For example, Google's current connection flow supports account sign-in, and Work's current page title is already “Work.” Those older screenshot labels are not reported as current defects.

No running emulator window was available during this review. Fresh device interaction, TalkBack, landscape, large text, reduced motion, and account sign-in were not tested. Source-derived interaction problems and visual observations are identified accordingly.

## Prioritized improvements

### 1. Make first setup one guided choice — P1

**Observed in source:** Chat says “Get a recommended model,” but opens the Models overview with Phone, Heavy phone, and Home choices. Inside a phone choice, people see search, downloaded models, and up to eight popular choices. The button promises more guidance than the destination provides.

**Change:** For an unconfigured phone, offer one clearly identified starting option with download size, storage requirement, and an honest suitability explanation. Use **Download & get started**, then return to the preserved chat draft when ready. Keep **Browse other models** available. If no suitable recommendation can be established, explain that and offer browsing rather than inventing confidence.

Mark Heavy phone and Home as optional. A useful offline chat should not look as though it requires three configured models. On established installations, keep the existing Models choices accessible.

**Success check:** A new user reaches a usable chat without needing to understand GGUF, quantization, inference locations, or response-mode assignments.

Evidence: [chat setup](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L394), [model overview](../app/src/main/java/dev/pocketmuse/ModelsScreen.kt#L31), [phone choices](../app/src/main/java/dev/pocketmuse/ModelsScreen.kt#L118).

### 2. Make Work about what needs to happen next — P1

**Observed in source:** Work has an overview button plus four persistent tabs: Goals, Reminders, Scheduled, and Actions. Opening the overview selects index 4, outside those four tabs, so none of them represents the current view. The user must learn which execution category owns their request.

**Change:** Make the existing overview the clear default. Show **Needs you**, **Upcoming**, and **Recent results**, with concise rows and counts. Put type filters behind **Filter** or **All work**. Retain the existing specialized editors behind each item's details.

Keep execution differences visible in relevant details: **Runs while this chat is open**, **Scheduled on this phone**, or **Running on Home**. A common list must not imply that legacy chat goals continue in the background.

**Success check:** Someone can find the item requiring approval without deciding whether it is a goal, action, or scheduled prompt.

Evidence: [Work navigation](../app/src/main/java/dev/pocketmuse/TasksScreen.kt#L25), [overview](../app/src/main/java/dev/pocketmuse/WorkOverview.kt#L16).

### 3. Open the exact work item and order Upcoming by time — P1

**Observed in source:** Overview rows such as “Review actions” pass only a tab index. The selected item is not passed to the destination. Results from local steps similarly open Notes or Reminders as a category. “Coming up” appends actions, then reminders, then scheduled prompts; it does not globally sort these items by time.

**Change:** Tapping a row should open that exact approval, task, or saved result. Merge dated Upcoming rows into one chronological order and separate overdue items. Use one main action per row: **Review**, **Continue**, or **View result**. Show a small Work attention count in the main navigation when input is required.

**Success check:** From Work, an approval or result is reachable in one tap, with no second search through a category. A reminder due in ten minutes appears before a task due tomorrow.

Evidence: [overview callbacks and ordering](../app/src/main/java/dev/pocketmuse/WorkOverview.kt#L25), [result routing](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L230), [local result links](../app/src/main/java/dev/pocketmuse/DurableWorkScreen.kt#L53).

### 4. Make Back behave consistently — P1

**Observed in source:** In an Inbox detail, Android Back closes the detail, while the shared top back arrow changes the page from Inbox to Library. The detail therefore adds a second “Back to Inbox” text button. Opening a source from Knowledge also switches the destination to Inbox without retaining the search as the return destination. From Models → Home → Connect Home, the shared back rule returns from Connections to Settings.

**Change:** Preserve actual navigation history and item identity. Both Back controls should take one understandable step back. Return from a source to its original search, with query, results, and scroll position retained. Return from Home connection setup to the model setup that opened it. Remove duplicate back links once the shared control handles these cases.

**Success check:** Top Back and Android Back produce the same result in each nested flow; users can inspect evidence and return to where they were.

Evidence: [shared back rules](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L192), [Inbox back handling](../app/src/main/java/dev/pocketmuse/InboxScreen.kt#L60), [source navigation](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L234).

### 5. Turn Library into a useful destination — P1

**Observed in source:** Library is five category links, with no content, search, or capture control. Notes has its own search; source/knowledge search lives inside Knowledge & projects. Memory is reachable through Library and through About you, where it appears both as a tab and a navigation row.

**Change:** Lead Library with **Search your library**, **Save something**, a compact review count, and recent saved items. Offer source, note, project, and personal-detail filters rather than requiring category selection before searching. Keep the underlying stores and their meaning distinct; mark facts used in future chats as **Used by Cina** and source material as **Saved reference**.

Give personal memory one canonical editor, with a shortcut from About you. Move **Storage & backup** to Settings, retaining an explicit name such as **Captured library backup** until backup actually covers all app data.

Adding cross-library search is new functionality: the existing knowledge search must not be relabeled as though it already searches notes, memories, and chats.

**Success check:** Users can locate a saved thought without remembering which kind of record it became. They can also tell whether saving it changes future conversations.

Evidence: [Library](../app/src/main/java/dev/pocketmuse/LibraryScreen.kt#L8), [source search](../app/src/main/java/dev/pocketmuse/ContextLibraryScreen.kt#L53), [profile memory entry points](../app/src/main/java/dev/pocketmuse/YouScreen.kt#L38).

### 6. Give source suggestions an obvious outcome — P2

**Observed in source:** A suggestion presents View source quote, Edit, Keep, Defer, and Discard. After Keep, a fact gains “Save as knowledge”; an action gains “Create task for review.” The distinction is explained, but the primary action is still vague. Projects and setup guidance appear before the suggestion review.

**Change:** Put a short source summary and the suggestions first. Collapse project filing and technical extraction details. Use outcome-specific actions: **Save fact…**, **Create task…**, or **Use category**, followed by the appropriate review. Put **Edit** and **Later** at secondary emphasis, and dismissal in an overflow menu. Confirm the result with a link to what was saved.

Keep accepting information separate from permission to execute work. A “Create task” action may prepare a reviewed draft; it must not silently authorize execution. Preserve the existing review-only state for people who want to keep an interpretation without promoting it.

**Success check:** Users can explain what the main button will do without reading the paragraph above the card. Saving a fact and approving an action remain visibly different.

Evidence: [suggestion card](../app/src/main/java/dev/pocketmuse/IntakeReviewCard.kt#L17), [promotion controls](../app/src/main/java/dev/pocketmuse/InboxScreen.kt#L78).

### 7. Let chat dominate the chat screen — P2

**Observed in source:** The header exposes menu, companion customization, conversation switching, new chat, overflow, model selection, and a response-mode selector. Home adds a disclosure line and a status line that tells people controls are in Work, without linking there. The list scrolls to the latest item whenever message count or busy state changes, regardless of whether the person is reading older messages.

**Change:** Use one conversation title/history control and one New chat action. Keep the companion small, with customization in Settings. Consolidate routine response control into one **Auto** menu; show model names and assignments in its details. Retain visible execution-location and data-sharing information when Home is used. Make running work a compact, tappable status card that opens the exact item.

Keep the composer to attachment, message, and send. Give web access a clear labeled state when enabled. If someone scrolls up, preserve their position and show **New reply ↓** instead of forcing them down.

**Success check:** An ordinary message requires no model decision. Older replies remain readable while a new reply arrives.

Evidence: [chat controls](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L349), [Home status](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L390), [scroll behavior](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L335).

### 8. Protect writing and explain invalid fields — P1/P2

**Source-confirmed problem:** In the local task editor, switching Note → Reminder → Note creates replacement `WorkAction` values with only kind and title. The previously typed note body or reminder time is lost. The editor also shows one generic validation message for all remaining steps, leaving the user to find the invalid field.

**Change:** Preserve separate values for each step type. Show errors beside the relevant title, body, or time, identify the invalid step, and focus it after an attempted save. Use a full-screen editor for multi-step plans and long notes, with a stable Save control and a clear next action. Keep short confirmations in dialogs.

Notes can dismiss without saving; reopening New note explicitly clears its draft. Add recoverable drafts or a dirty-form dismissal choice. Do not rely on `rememberSaveable` alone as a persistent draft system.

**Success check:** Changing a step type and switching back preserves typed content. An interrupted note can be recovered. The editor identifies the exact step and field preventing a save.

Evidence: [step type replacement](../app/src/main/java/dev/pocketmuse/DurableWorkScreen.kt#L112), [generic validation](../app/src/main/java/dev/pocketmuse/DurableWorkScreen.kt#L131), [note creation and dismissal](../app/src/main/java/dev/pocketmuse/NotesScreen.kt#L34).

### 9. Make deleting a memory recoverable — P1

**Observed in source:** Saved memory rows call `deleteMemory` immediately. The handler deletes the database entry with no confirmation or undo. Notes and captured sources already use deletion confirmations.

**Change:** Provide **Undo** after ordinary single-memory deletion, or a concise confirmation if recovery cannot be supported. Separate Delete from Edit visually. Keep stronger confirmation for deleting a source and its linked knowledge.

**Success check:** One mistaken tap cannot permanently remove a personal detail without a recovery opportunity.

Evidence: [memory row](../app/src/main/java/dev/pocketmuse/MemoryScreen.kt#L54), [delete handler](../app/src/main/java/dev/pocketmuse/AssistantViewModel.kt#L496).

### 10. Standardize controls, layout, and feedback — P2

**Observed in source and captures:** Important metadata often uses 11–12sp. The clickable chat companion is visually 40dp and color swatches are 38dp with no explicit 48dp container. Shared controls use text glyphs for menu, back, plus, and arrows. Many pages repeat a brand bar, large title, subtitle, and introductory gap before actionable content. The recent model capture also shows the Models title partly hidden beneath a status banner; fresh reproduction is needed before assigning a layout cause.

**Change:** Preserve generous touch areas while making routine settings rows more compact. Use consistent vector icons, clear button roles, selected/expanded semantics, and decorative-icon hiding. Target 16sp body text and 14sp useful secondary labels; reserve smaller type for nonessential metadata. Put transient success feedback in a snackbar or near the initiating control. Put persistent failures beside their recovery action. Keep page titles fully visible when feedback appears.

The base palette already has good calculated contrast: Ink/Paper **12.80:1**, Muted/Paper **5.74:1**, Muted/Soft **5.35:1**, and Accent/Paper **4.92:1**. These are opaque token calculations, not a complete rendered accessibility test. Readability work should focus first on type size, hierarchy, and controls rather than replacing the palette.

**Success check:** Custom interactive controls have at least 48dp touch areas, relevant controls announce purpose/state correctly in TalkBack, and large text does not conceal the current page or main action. Android's [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) describe native touch sizing and semantics.

Evidence: [shared components](../app/src/main/java/dev/pocketmuse/UiComponents.kt#L24), [custom controls](../app/src/main/java/dev/pocketmuse/MainActivity.kt#L293), [swatches](../app/src/main/java/dev/pocketmuse/YouScreen.kt#L94), [banner capture](../preview/models-2026-10-24/model-choices-connected.png).

## Secondary improvements

| Area | Recommendation | Evidence / verification needed |
| --- | --- | --- |
| Main navigation | Keep the four existing destinations. Try labeled bottom navigation for Chat, Library, Work, Settings on compact phones, and a rail on wider layouts. Hide it while the chat keyboard is open. Compare discoverability and conversation space before adopting it. | Current destinations are behind the menu; this is a proposed interaction change, not a proven defect. |
| Phone, Fold, landscape | Constrain reading width and adapt list/detail layouts to available space. Four equally weighted Work tabs and the multi-control chat header need narrow-width and large-text checks. | Shared `Page` fills the available width; no window-size adaptation was found. Follow [Android window-size guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes). |
| Dark appearance | Add System / Light / Dark using semantic surface and text colors, then test each palette. | `MainActivity` always installs a light scheme and light system bars. Companion appearance currently changes the character, not the app theme. |
| Growing libraries | Use lazy lists for Notes, Memory, and Work as collections grow. Keep stable item keys and scroll state. | These collections currently render all their rows inside a vertically scrolling `Page`; actual performance has not been measured. |
| Approvals | Lead with action, destination, affected item, and changes. Keep editable content visible; place persistent “allow in future chats” controls behind an explicit permissions expansion. | Current approvals are editable and preserve exact details; retain that strength. |
| Wording | Prefer “Find useful details” over “Interpret with local model,” “Later” over “Defer,” and user-facing task states over raw state strings. | Keep execution, data destination, and approval scope explicit in context. |

## Suggested delivery order

1. **Repair trust and navigation:** consistent Back, exact-item Work links, chronological Upcoming, recoverable memory deletion, and preserving step content.
2. **Reduce decisions:** guided model setup, a clear Work default, outcome-specific suggestion actions, and simpler chat controls.
3. **Improve finding and saving:** useful Library home, cross-library search, and a consistent capture entry point.
4. **Polish and validate:** typography, vector controls, compact settings, adaptive layouts, dark appearance, and growing-list behavior.

The first group can be delivered without a new visual identity or a shared execution-engine rewrite. Cross-library search and new onboarding are larger feature changes and should be scoped separately.

## Usability checks for implementation

Use a fresh install and a populated installation. Verify that a person can:

- Start a chat after one guided setup, with any existing draft preserved.
- Save something without choosing its final information category first.
- Find a saved item, inspect its source, and return to the same search position.
- Open the exact pending approval from Work and identify what approving it will do.
- See what continues when they close the app, and distinguish an unreachable Home from a stopped job.
- Read older replies without incoming work moving their position.
- Recover an accidentally deleted memory or interrupted note.
- Complete these flows at 200% text, on a small phone, unfolded Fold, and in landscape, including TalkBack and a hardware keyboard.

Use [Android navigation principles](https://developer.android.com/guide/navigation) for consistent back behavior. Device checks above remain acceptance criteria, not tests completed by this review.

## Skill application

Applied `ui-ux-pro-max` with the detected **Jetpack Compose** stack. Relevant verified search results covered mobile Back behavior, Compose semantics, and Android touch targets. Read the skill's full critical/high-priority rules and native-app checklist.

The progressive-disclosure search returned an unrelated typography result after a narrower retry, so that recommendation uses the skill's built-in Forms & Feedback rule. The generated design-system suggestions remained oriented toward web marketing layouts after a retry; those patterns and replacement palettes were not adopted or persisted. The proposed visual direction preserves Cina's existing identity and uses the skill's built-in simplicity and hierarchy guidance.
