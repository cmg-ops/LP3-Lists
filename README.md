# Lists for Light Phone 3

A DailyHobbyist tool. Expandable, infinitely nestable lists — every item
can open into its own list, as deep as you want.

**Package:** `com.dailyhobbyist.lists` · Built natively with the
[Light Phone SDK](https://github.com/lightphone/light-sdk)
(Kotlin + Jetpack Compose).

## Features

- Home screen of lists; tap any item to expand it into its own list,
  with no limit on nesting depth
- Per-list **checklist mode**, or turn an entire group (a list and
  everything nested inside it) into checklists in one tap
- Hand-drawn style: checked items get a pencil-scribbled circle and a
  thin strike through the name
- Purge checked items — for one list, or the entire group at once,
  each with a tap-twice confirm and a live count
- Edit mode with rename, move up/down, and one-tap delete (circled ✕)
- Pure LP3 aesthetic: black background, full-brightness white, no color
- Fully offline — data saved on-device, no permissions needed

## Where the app lives

All app code is in [`tool/`](tool/) — see
[`tool/README-LISTS.md`](tool/README-LISTS.md) for full usage and
build/install instructions. The rest of this repository is the Light
Phone SDK the app is built on.

## Quick build

```
./gradlew :tool:assembleDebug
adb install -r tool/build/outputs/apk/debug/tool-debug.apk
```
