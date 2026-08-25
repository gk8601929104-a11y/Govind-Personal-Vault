# Govind Personal Vault

Govind Personal Vault is an offline Android 12+ application for passwords, secure notes, encrypted private media, and encrypted documents. It uses a 100% programmatic Android UI: there are no layout XML files.

## Current release: 1.3.2 (versionCode 15)

### Version 1.3.2 — Home, titles, and viewers

- Overview dashboard with tappable counts. Passwords title stays on one line.
- Tap a document or photo to hide tools instantly; status bar hides while the file is open.
- Video seek is smooth: drag shows time, release seeks once.

### Version 1.3.0 — Aegis-inspired shell

- Bottom navigation: Overview, Passwords, Notes, Cards, Files.
- Dark/Light, auto-lock while idle, clipboard timing, destroy vault.
- Cards and category chips. Existing encrypted files still open in the secure viewers.

### Version 1.2.7 — Cleaner chrome


- Tap a photo, video, audio, or document to hide overlay buttons. Tap again to bring them back.
- Search is a small top icon. Long tab banners are gone.

### Version 1.2.6 — File-first viewers


- Photos, videos, audio, and documents open full-screen. Extra title/filename/hint chrome is gone.

### Version 1.2.5 — Premium visual refresh


- New design system: ink background, jewel mint accent, hairline cards, and quieter chrome.
- Every screen uses the same components. Unlock, vault, media, documents, player, and settings keep the same features.

### Version 1.2.4 — Player chrome and settings polish


- Settings About card shows the installed version from the APK, not a hardcoded 1.0.0.
- Encrypted player overlay is compact and auto-hides during video. Tap to show controls.
- Portrait/tall videos default to Fit so landscape Zoom does not crop faces.
- Long image/media file names ellipsize instead of breaking the extension.

### Version 1.2.3 — QA screenshots and stronger encrypted player

- Screenshot blocking (`FLAG_SECURE`) is temporarily off so device screenshots can be captured for QA. It will be turned back on when the app is marked final.
- Encrypted audio and video now autoplay, start from a smaller buffer, and show a buffering spinner.
- Player controls overlay the media. Double-tap left/right skips 10 seconds. Horizontal swipe seeks. Left-half vertical swipe adjusts brightness (video). Right-half vertical swipe adjusts volume.
- Speed includes 0.75×. Encryption, package ID, signing, and permissions are unchanged.


### Version 1.2.2 — In-app zoom and document open

- Encrypted photos open with pinch-zoom, pan, double-tap zoom, and left/right slide to the next photo.
- Encrypted documents open inside the app (PDF pages, text, and Office text preview). No plaintext temp file and no external viewer intent.


### Version 1.2 — Landscape UI, immersive player, and Documents Vault


- The dashboard is implemented as one scrollable programmatic `ListView` surface, so its stats, categories, search, items, empty state, and add action remain reachable in tablet/landscape layouts.
- The encrypted video player enters immersive landscape mode, hides app/system chrome, provides Fit/Zoom/Fill resize modes, and auto-hides playback controls while video is playing.
- Added a separate Documents Vault for PDFs, office files, text files, archives, and other non-media documents selected through the Storage Access Framework.
- Documents reuse the authenticated encrypted internal-storage format and encrypted SQLite metadata.
- PDFs and text-like documents can be read inside the vault. Export still decrypts a copy into `Downloads/Govind Personal Vault` when another app is needed.

### Phase 1 — Passwords and secure notes

- Six-to-twelve digit PIN with persisted throttling.
- Optional strong-biometric unlock through an auth-per-use Android Keystore key.
- Twelve-word BIP39-checksummed recovery phrase, shown once and never stored.
- Random 256-bit vault master key wrapped independently for PIN, recovery, and optional biometric unlock.
- AES-256-GCM per-field encryption with fresh IVs and contextual AAD.
- Serialized background SQLite access.
- Encrypted editor drafts, background auto-lock, `FLAG_SECURE`, backup exclusion, and no Internet permission.

### Phase 2 — Media Vault

- Multi-select document picker for images, videos, and audio without broad storage permissions.
- Encrypted files stored only under `getFilesDir()/media/`.
- GVM2 seekable media format with:
  - one-megabyte bounded chunks,
  - per-file HKDF-SHA256 keys derived from the unlocked VMK,
  - independent AES-256-GCM authentication for every chunk,
  - authenticated file header,
  - atomic `.part` to final-file commit.
- SQLite stores only encrypted original name, MIME type, size, and encrypted thumbnail bytes plus non-sensitive ID/timestamps.
- Programmatic encrypted-media gallery with filters, search debounce, thumbnails/icons, progress, and empty state.
- Secure sampled image viewer that decrypts from a bounded stream without a plaintext temp file.
- AndroidX Media3/ExoPlayer video and audio player using a custom seekable `EncryptedCipherDataSource`.
- Media is decrypted directly into the player buffer; it is never handed to an external player and never written to disk in plaintext.
- Export decrypts directly into a pending MediaStore row and publishes it only after success.
- Permanent encrypted-media deletion with rollback-aware file/metadata ordering.

## Security boundaries

- No `INTERNET` permission.
- No ads, analytics, telemetry, or cloud sync.
- No broad media/storage permission.
- The original selected file is not deleted automatically after import; the user must remove it from public storage if desired.
- `FLAG_SECURE` reduces accidental screenshots/screen recording but cannot defend against a rooted device, malware with equivalent access, or an external camera.
- Supported containers/codecs depend partly on Android Media3 extractors and the device's available media decoders. Unsupported codecs fail without creating a plaintext copy.
- This project has not received independent commercial cryptographic certification.

## Build system

Version and dependency metadata are in `app/version.properties`. Normal future releases require a higher `VERSION_CODE` and an updated `VERSION_NAME`; the GitHub workflow discovers the source ZIP and output filename automatically.

The project uses:

- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- compile SDK 36 / target SDK 35 / min SDK 31
- AndroidX Activity 1.13.0
- AndroidX Media3 1.10.1
- Java 17 source compatibility

### GitHub Actions build

Use `.github/workflows/build-apk.yml`. The repository must contain exactly one matching public source ZIP. Signing material is supplied only by these encrypted repository secrets:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Run:

`Actions → Build signed APK → Run workflow → main → Run workflow`

The signed artifact is named from `VERSION_NAME`.

### Local build

Install the configured Gradle version and Android SDK packages, export the four signing environment variables, and run:

```sh
./scripts/run_tests.sh
./scripts/security_audit.sh
./scripts/build_apk.sh
./scripts/verify_apk.sh
```

The signed APK is written to `dist/Govind_Personal_Vault_v1.2.1.apk`.

Never commit a keystore, signing password, Base64 signing key, PIN, recovery phrase, vault database, or exported private media.

Designed and developed by Govind.


## v1.2.1 player refinements

Landscape playback removes retained gesture-inset space, defaults to Zoom for an edge-to-edge tablet canvas, and supports vertical swipe gestures: left side controls per-window brightness and right side controls media volume. These controls add no new Android permission and do not change encrypted streaming.
