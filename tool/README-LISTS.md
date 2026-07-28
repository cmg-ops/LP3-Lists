# Lists for Light Phone 3

**Package:** `com.dailyhobbyist.lists` · **Version:** 3.4 (v28)

Expandable lists, built natively with the Light Phone SDK (Kotlin + Jetpack Compose).

## How it works

- **Home screen** shows all your lists. Tap **+** to add one.
- **Tap a list** to open it — the list's name appears in the top middle.
- **Tap any item** to expand it into its own list. Add items inside, then
  tap those to go deeper. There's no limit to how deep you can nest.
- Items containing sub-items show a count on the right (e.g. `3 ›`).
- **Back** (top left) steps you back out one level at a time.

## Checklists

- Open a list and tap the **gear** (bottom center) for that list's settings.
- **CHECKLIST (LIST ONLY)** gives every item in that list a checkbox.
- **CHECKLIST (ENTIRE GROUP)** turns this list AND every list nested
  inside it (all the way down) into checklists in one tap. Toggling it
  off turns them all back into regular lists.
  Tap the checkbox to check/uncheck; tap the item's name to open it.
- **PURGE CHECKED (LIST ONLY)** appears in settings when checklist is on —
  it removes every checked item in that list (tap twice to confirm).
- **PURGE CHECKED (ENTIRE GROUP)** removes every checked item in this
  list and in every list nested inside it, at every level (tap twice
  to confirm). Both purge rows show a live count of checked items.

## Spellcheck

- Misspelled words are underlined as you type (only the misspelled
  ones — the rest of the text is clean).
- Long-press an underlined word to see up to three suggestions,
  shown large and centered; tap one to replace the word, or tap the
  chevron to dismiss.
- Powered by a 120,000-word English dictionary built into the app —
  no OS services, no network, works entirely inside Light's sandbox.

## Copy, paste & feedback

- Long-press anywhere while typing for COPY ALL / PASTE (plus spelling
  suggestions when pressed on a misspelled word).
- Menu rows (rename, move up/down, purge...) flash inverted for a beat
  when tapped, so selections are unmistakable.
- Haptic tick on every keyboard press and button.

## Backup & restore

- In any list's gear settings, **BACKUP / RESTORE**:
  - **Export** writes all your lists to `lists-backup.json` in the
    phone's shared folder.
  - **Restore** reads that file back (tap twice to confirm — it
    replaces everything).
- The file format matches the Android version, so a backup from one
  can be restored into the other.

## Path & moving

- The top bar shows your full location, like HOME/FAMILY/WIFE/GIFTS.
  If it's too long, the front is trimmed to `…/` so the current spot
  always shows.
- In an item's options (edit mode > tap an item): **MOVE TO LIST…**
  opens a picker to drop it into any other list at any depth; **COPY**
  puts a copy (with everything inside it) on the clipboard.
- In edit mode, if something's on the clipboard, a **PASTE HERE** row
  appears at the top of the list. Paste makes a fresh copy, so you can
  paste the same thing into several places.

## Sorting (ABC-123)

- Tap the **sort icon** in the bottom bar to prioritize a list.
- **Phase 1 — Letters:** every item shows A / B / C chips. Tap to
  assign a priority group; tap again to clear, tap another to switch.
  Skipped items get no label.
- **Phase 2 — Numbers:** one letter group at a time, each item shows
  number chips. Tap the spot you want — taking a spot shifts everything
  at that number and below down one, so inserting ahead of something is
  always one tap. Tap your own number to take it off.
- Finish and the list snaps into order: A1, A2, B1... with unlabeled
  items at the bottom. Labels stay visible, like "A1 milk".
- Re-running sort starts pre-filled with the current labels.
- **CLEAR SORT** in the gear settings strips all labels off a list.

## Editing

- Tap the **pencil** in the bottom bar to enter edit mode ("EDIT" shows
  under the title). Tap the checkmark to leave it.
- In edit mode, every item shows a circled **✕** on its right — tap it to
  delete that item (this also deletes everything nested inside it).
- Tapping an item's name in edit mode opens its options:
  **RENAME · MOVE UP · MOVE DOWN**

## Storage

Everything is saved on-device automatically (DataStore, one JSON blob).
Fully offline — no permissions needed.

## Build & install

This is the complete light-sdk project with Lists in the `tool/` module.

1. Unzip, open the folder in Android Studio
2. Let Gradle sync
3. Build → `./gradlew :tool:assembleDebug` (or Run from Android Studio)
4. Install the APK from `tool/build/outputs/apk/debug/` onto the LP3 via adb:
   `adb install -r tool-debug.apk`

`lighttool.toml` is set to `serverPackage = "com.lightos"` for the real phone.
For the SDK emulator, switch it to `com.thelightphone.sdk.emulator`.
