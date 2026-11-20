# AGENTS.md — OpenDex

## Purpose & Display Targets

**Goal:** support desktop mode on as many Android devices as possible. The desktop experience (dock, free-form windows, mouse/keyboard input) must work regardless of which display it runs on:

1. **On-device display** — desktop mode directly on the phone/tablet's built-in screen.
2. **External display, wired** — USB-C / DisplayPort Alt-Mode, HDMI adapters, docks/hubs.
3. **External display, wireless (cast over Wi-Fi)** — Miracast / Wi-Fi Display, Chromecast/Google Cast, and vendor wireless-desktop features (e.g. Wireless DeX).

When an OEM already provides a desktop environment (Samsung DeX, Motorola Ready For, …), OpenDex runs inside it; where none exists, OpenDex provides the desktop workspace itself on whatever display is active.

### Rules

1. **Never assume a single display.** Use the `Display` from the current `Context`/configuration; do not hardcode display ID `0` or the built-in screen's size/density/orientation.
2. **Detect capability, not vendor.** Branch on runtime capability (secondary display present, free-form windowing supported, pointer/keyboard attached) — not on brand/model checks (`Build.MANUFACTURER`, DeX-specific flags) unless there is no alternative. Desktop mode must degrade gracefully on any device ≥ `minSdk`.
3. **Be display-agnostic.** Core desktop features (dock, window management, app launching, input handling) must not depend on whether the display is built-in, wired, or Wi-Fi-cast. The transport only affects latency/quality, not behavior.
4. **Support mouse + keyboard AND touch on every display.** Hover, right-click, scroll, and keyboard navigation (Tab/arrows/Enter/Esc) must work, with touch equivalents, no matter the display type.
5. **Adapt to any display metrics.** Layout must handle arbitrary resolution, aspect ratio, and density of external/cast displays, and survive configuration changes (resize, rotation) without losing window state or user data.
6. **Handle display connect/disconnect gracefully.** Plugging/unplugging a cable or starting/stopping a cast must never crash, lose pinned apps/notes, or unexpectedly launch apps on the wrong display. Persist workspace state and restore it on whichever display becomes active.
7. **Open apps in OpenDex windows.** Any app or system screen launched from OpenDex should open inside an OpenDex window rather than fullscreen whenever windowed launch is available.
8. **Verify on all three targets.** Follow the manual matrix in `docs/device-compatibility.md` (on-device + wired external) and extend it with Wi-Fi cast cases when testing display-related changes. A feature is not "supported" until it is observed working on the target display — don't infer success from a display merely being detected.

## Tech Stack

- **Language: Kotlin** — all app code is written in Kotlin (no Java for new code).
- **UI: Jetpack Compose** — declarative UI with Material 3 (`androidx.compose.material3`). Build new UI as `@Composable` functions; do not introduce XML layouts or the legacy View system for new screens.
- **Platform:** Android — `minSdk 29`, `compileSdk/targetSdk 36`, JVM 17 (see `app/build.gradle.kts`).
- **Key AndroidX libraries already in use:** `activity-compose`, `lifecycle-runtime-compose` / `lifecycle-viewmodel-ktx`, `datastore-preferences`. Prefer these and Compose BOM-managed artifacts over adding new third-party libraries; check `app/build.gradle.kts` before adding any dependency.

### Rules

1. Follow Compose best practices: unidirectional data flow (state down, events up), state hoisting, and side effects via `LaunchedEffect`/`DisposableEffect` — never call suspend/IO work directly in a composable body.
2. Keep composables small and previewable; put business logic in ViewModels, not in composables.
3. Use Material 3 components and theming (`MaterialTheme`) instead of custom-drawn views or hardcoded colors/dimens.
4. All user-facing strings in Compose must come from resources via `stringResource(...)` (see "Languages (i18n)" below).

## Languages (i18n)

- **Supported languages (only 2 for now):**
  - English (`en`) — **default / fallback language**
  - Vietnamese (`vi`)
- Do not add any other language/locale unless explicitly requested.

### Rules

1. **Default is English.** If the device/user locale is not Vietnamese, or a translation is missing, fall back to English.
2. **Android string resources:**
   - `app/src/main/res/values/strings.xml` — English (default)
   - `app/src/main/res/values-vi/strings.xml` — Vietnamese
3. **No hardcoded user-facing strings** in Kotlin/Compose code. Use `stringResource(R.string.…)` (and plurals/placeholders from resources) for all UI text, content descriptions, notifications, toasts, and error messages shown to users.
4. **Keep both locales in sync.** Every time a string key is added, renamed, or changed, update **both** `values/` and `values-vi/` in the same change:
   - Same key names in both files.
   - Same format placeholders (`%1$s`, `%1$d`, …) and plural quantities.
   - Mark non-translatable strings with `translatable="false"` in the default file.
5. **Locale-aware formatting:** format dates, times, numbers, and plurals using the active locale — never hardcode `Locale.US` for user-visible output (use it only for machine-readable/persisted formats).
6. **Vietnamese quality:** use proper Vietnamese diacritics (tiếng Việt có dấu đầy đủ), natural wording — do not leave English placeholders in `values-vi/` (except `translatable="false"` strings).
7. **Code & docs:** identifiers, comments, log messages, and internal documentation are written in English. Only user-facing copy is localized.
