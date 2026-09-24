# Inkfold

A pen-first note-taking app for Android phones and tablets. Write with a stylus
or your finger, organise notes into multi-page files, and sync everything to a
folder in your Google Drive with a local cache for offline use.

This app is built with Google Play distribution in mind; the debug and (when
configured) signed release builds from CI can also be sideloaded directly.

## Features

- **Pen and finger input** — one pointer draws; two fingers scroll and zoom.
  Stylus pressure modulates pen stroke width.
- **Continuous vertical scrolling** through all pages of a notebook, on a
  full-screen canvas with a floating overlay toolbar.
- **Three tools**
  - **Pen** — adjustable colour and size, pressure-sensitive.
  - **Highlighter** — adjustable colour and size, semi-transparent so text
    underneath stays readable.
  - **Eraser** — adjustable size, with two modes: **delete objects** (removes
    whole strokes it touches) or **rub out to white** (paints opaque white).
- **Pen mode** toggle — when on, only a stylus draws and a single finger
  scrolls; when off, touch draws and two fingers scroll/zoom. Pen mode has
  **palm rejection**: finger input is ignored while the stylus is hovering or
  was just used, and palm-sized contacts are dropped.
- **Documents & pages** — create, open, rename and delete documents. Each
  document is a folder of pages described by an `index.inkfold` manifest;
  pages are independently **portrait or landscape**.
- **Mixed page types** — graphical (handwriting) pages saved as self-describing
  SVG, and **Markdown text pages** (`.md`), rendered inline in the same
  continuous scroll. Tap a Markdown page to edit it (`#` headings, `**bold**`,
  `*italic*`, `- bullets`, `` `code` ``).
- **Undo / redo** for strokes and erases.
- **Google Drive sync** — two-way sync to an `Inkfold` folder in your Drive,
  backed by a local cache so the app works fully offline. Syncs automatically
  every few minutes while the app is open (and on document close), plus a
  periodic background sync via WorkManager while it's closed, plus on demand.
  Conflicts resolve last-write-wins by modification time.
- **Export** — save a notebook **PDF** to a chosen location via the system file
  picker, or share it; export each page as a **JPG**.
- **Responsive** — a grid library on the home screen and a canvas that fits any
  screen, so it works on both phones and tablets.
- **Light/dark theme**, with Material You dynamic colour on Android 12+.

## Tech overview

- Kotlin, Jetpack Compose for the UI shell, with a custom `View`
  (`DrawingView`) for the drawing surface (reliable `MotionEvent`/stylus
  handling).
- **Document format.** A document is a *folder*, not a single file:
  ```
  documents/<id>/
    index.inkfold      # JSON manifest: ordered list of pages, each with a type
    <pageId>.svg        # one self-describing SVG per page
  ```
  The `index.inkfold` manifest (kotlinx.serialization) lists pages in render
  order; each entry has a `type`, either `svg` (handwriting) or `markdown`
  (text). Each page SVG renders in any viewer **and** embeds the exact
  stroke data (pressure, tool, colour) in `<metadata>`, so the app reloads it
  losslessly. This folder is also the offline cache. See `model/Notebook.kt`,
  `storage/DocumentRepository.kt`, and `drawing/SvgPage.kt`.
- Drive access uses the Play Services `AuthorizationClient` API to grant the
  `drive.file` scope (a system account picker chooses the Google account, then
  a consent screen requests Drive access if it isn't already granted), and the
  Drive v3 REST API over OkHttp for sync itself. Each document is a sub-folder
  of an `Inkfold` Drive folder; the app lists those sub-folders as documents.
  The app can only see files it creates.
- Export uses Android's `PdfDocument` and `Bitmap` APIs. On-screen and exported
  rendering share one code path (`drawing/StrokeRenderer.kt`).

```
app/src/main/java/app/inkfold/
├── MainActivity.kt            # Compose host + sign-in launcher
├── model/Notebook.kt          # Notebook / Page / Stroke + manifest model
├── drawing/
│   ├── DrawingView.kt         # custom canvas: input, scroll/zoom, undo/redo
│   ├── StrokeRenderer.kt      # shared stroke painting (editor + export)
│   └── SvgPage.kt             # page <-> self-describing SVG (round-trip)
├── storage/DocumentRepository.kt   # folder + SVG store / offline cache
├── sync/DriveSync.kt          # Google Drive folder-per-document sync
├── export/Exporter.kt         # PDF + JPG export
└── ui/                        # Compose screens, view model, theme
```

## Building

You need JDK 17 and the Android SDK (`compileSdk` 36). The Gradle wrapper is
checked in.

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install on a connected device
./gradlew installDebug
```

Open the folder in Android Studio (Koala or newer) for day-to-day development.

See [`CLAUDE.md`](CLAUDE.md) for the project's style guide (Kotlin/Android
conventions and commit message format) — it applies to human and AI
contributions alike.

## Continuous integration

`.github/workflows/build.yml` runs on every push and pull request (and via
manual dispatch). It:

1. sets up JDK 17 and the Android SDK,
2. builds the debug APK (`assembleDebug`),
3. runs Android Lint (`lintDebug`),
4. uploads the APK as a build artifact (`inkfold-debug-apk`),
5. when the release keystore secrets are configured (see below), also builds
   a signed release APK (`inkfold-release-apk`) and a signed release AAB
   (`inkfold-release-aab`) — the AAB is the format the Play Console requires
   for a Play Store upload.

Download the APK from the **Artifacts** section of a completed run and sideload
it onto your device.

### Claude-assisted issue triage

Two workflows let Claude turn a GitHub issue into a pull request, with a
human approval step *before* any AI call is made, so nothing costs API usage
or gets implemented unasked:

- **`claude-issue-plan.yml`** — when an issue is opened, posts a static
  comment: *"This will be addressed by Claude. The repository owner needs to
  comment `/approve` to continue."* No AI call here — it's a plain GitHub
  Actions script, so this step is free and doesn't need any secret.
- **`claude-issue-implement.yml`** — only when the repo owner comments
  exactly `/approve` on that issue does Claude get invoked at all: it reads
  the issue, implements it, pushes a branch, and opens a pull request
  referencing the issue. It never merges; the PR itself is the second
  approval gate, reviewed and merged like any other.

`claude-issue-implement.yml` needs an `ANTHROPIC_API_KEY` repository secret
(**Settings → Secrets and variables → Actions**) to call Claude; without it,
approving an issue just fails harmlessly (the `build.yml` CI workflow is
unaffected, and no AI usage is incurred either way until you comment
`/approve`).

## Enabling Google Drive sync

The app works without sign-in; sync is optional. To enable it you must create
your own OAuth client, because Google ties OAuth credentials to your app's
signing certificate.

1. In the [Google Cloud Console](https://console.cloud.google.com/) create a
   project and **enable the Google Drive API**.
2. Configure the **OAuth consent screen** (External is fine for personal use)
   and add your Google account as a **test user**. Add the
   `.../auth/drive.file` scope.
3. Create an **OAuth client ID → Android**:
   - **Package name:** `app.inkfold`
   - **SHA-1:** the fingerprint of the keystore the APK is signed with. For the
     debug build:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore \
       -alias androiddebugkey -storepass android -keypass android
     ```
     (CI-built APKs are signed with the runner's debug keystore, so register the
     SHA-1 of whichever keystore you actually install from — typically build and
     install locally for a stable fingerprint.)
4. Install the app, open the overflow menu on the home screen, and choose
   **Sign in to Google Drive**. You'll be asked to pick a Google account from
   the system account picker, then (the first time) to grant Drive access.
   Use the cloud icon to sync on demand.

No API keys or secrets are stored in the repo — Android OAuth clients are
identified by package name + signing certificate, not a secret.

### Using Drive sync with the CI-built APK (fixed signing keystore)

The debug APK from CI is signed with the runner's throwaway keystore, whose
SHA-1 you can't register. To make Drive sync work from a CI build, sign a
**release** APK with your own keystore stored in GitHub Secrets:

1. Generate a keystore locally (keep it safe; don't commit it):
   ```bash
   keytool -genkeypair -v -keystore inkfold-release.jks \
     -alias inkfold -keyalg RSA -keysize 2048 -validity 10000
   ```
   Choose a store password and key password (they may be the same).
2. Read its SHA-1 (register this in the OAuth Android client, step 3 above):
   ```bash
   keytool -list -v -keystore inkfold-release.jks -alias inkfold
   ```
3. Base64-encode the keystore for the secret:
   ```bash
   base64 -w0 inkfold-release.jks      # Linux
   base64 -i inkfold-release.jks       # macOS
   ```
4. In the repo: **Settings → Secrets and variables → Actions → New repository
   secret**, add:
   - `KEYSTORE_BASE64` — the base64 string from step 3
   - `KEYSTORE_PASSWORD` — the store password
   - `KEY_ALIAS` — `inkfold`
   - `KEY_PASSWORD` — the key password
5. Re-run the build. When the secret is present, CI also produces a
   **`inkfold-release-apk`** artifact, signed with your keystore. Install that
   one. (Uninstall any earlier debug build first — different signature.)

The same keystore SHA-1 also works for a local `./gradlew installDebug` only if
you point the debug build at it; simplest is to just use the release artifact.

### Troubleshooting sign-in

If the Drive consent screen appears, flickers, and closes without granting
access, the app will show **"Drive sign-in failed (error 10)"**. Error 10 is
`DEVELOPER_ERROR`: the certificate the installed APK is signed with has no
matching Android OAuth client in your Google Cloud project. Register the SHA-1
of the exact keystore you installed from (see step 3 above) and add your
account as a test user on the consent screen. Other codes: `12501` =
cancelled, `7` = network error.

## Notes & limitations

- The object eraser removes whole strokes it intersects (vector erase). The
  "rub out to white" eraser paints opaque white strokes on top rather than
  splitting the underlying ink, which is simple and looks correct on white
  pages.
- Sync is last-write-wins per notebook; it is not designed for simultaneous
  editing of the same notebook on two devices.
- Pages use an A4-proportioned coordinate space, so PDF/JPG exports come out at a
  consistent aspect ratio regardless of device.
