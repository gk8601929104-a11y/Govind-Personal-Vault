# Govind Personal Vault

Govind Personal Vault is an offline Android 12+ application for passwords, secure notes, encrypted private media, and encrypted documents. It uses a 100% programmatic Android UI: there are no layout XML files.

## Current release: 1.3.1 (versionCode 14)

- Aegis-inspired shell: bottom navigation, serif titles, cream buttons, Dark/Light.
- New Cards tab. Category chips and favorites on logins, notes, and cards.
- Files tab encrypts any file and opens it in the existing secure viewers.
- Settings: auto-lock while idle, clipboard clear timing, destroy vault.
- Build fix: removed obsolete `addHeaderView` check from security audit.
- Encryption, package ID, and in-app viewers are unchanged.

### Version 1.2.7 — Clean chrome

- File viewers: single tap hides overlay buttons, tap again shows them.
- Compact premium tabs: long banners and hint copy removed. Search is a top icon that expands the search field.
- Filter chips no longer wrap.

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
