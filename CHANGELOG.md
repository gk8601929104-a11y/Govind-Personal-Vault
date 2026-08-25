# Changelog

## 1.4.0 (version code 17)

- Overview now matches the Aegis-style home: greeting, Generate, health cards, Recent, Favorites, and Start with one secret.
- Hamburger drawer with counts, Generator, Settings, Lock, and theme toggle. Header search and lock stay on every tab.
- Files: title, category, favorite, tags, notes. Encrypt-file form. Detail panel with Edit, Open/Star/Delete menu, Download, and Export .enc.
- Settings: encrypted vault backup (.gpv), import backup, import .enc (GVM2). Master PIN still re-wraps the same data key.
- Password generator with copy and "use in new login". Weak/reused health uses real decrypted secrets in memory only while unlocked.
- Encryption is unchanged (AES-256-GCM / GVM2, offline, same package ID). Over-install on 1.3.x.

## 1.3.3 (version code 16)

- Overview counts now show real totals (Files was stuck at 0).
- Bottom nav stays pinned to the bottom on Overview, same as other tabs.
- Back from Passwords / Notes / Cards / Files returns to Overview instead of leaving the app.

## 1.3.2 (version code 15)

- Overview is a dashboard: tap Passwords / Notes / Cards / Files tiles. Bottom nav stays pinned.
- Tab titles stay on one line (Passwords no longer wraps). Filter chips scroll fully.
- PDF and text: tap immediately hides or shows back/export/delete. Status bar hides in file viewers.
- Video finger-seek previews the time, then jumps once when you lift — no mid-drag scramble.
- Encryption is unchanged (AES-256-GCM, offline).

## 1.3.1 (version code 14)

- Build fix: removed obsolete `addHeaderView` check from security audit (Aegis shell no longer uses ListView header).
- No functional or encryption changes from 1.3.0.

## 1.3.0 (version code 13)

- Aegis-inspired shell: bottom navigation, serif titles, cream buttons, Dark/Light.
- New Cards tab. Category chips and favorites on logins, notes, and cards.
- Files tab encrypts any file and opens it in the existing secure viewers.
- Settings: auto-lock while idle, clipboard clear timing, destroy vault.
- Encryption, package ID, and in-app viewers are unchanged.

## 1.2.7 (version code 12)

- File viewers: single tap hides overlay buttons, tap again shows them.
- Compact premium tabs: long banners and hint copy removed. Search is a top icon that expands the search field.
- Filter chips no longer wrap. Media, documents, dashboard, and settings keep the same features and encryption.

## 1.2.6 (version code 11)

- Opening a photo, video, audio, or document now fills the screen with the file. Title, filename, MIME, and hint text are removed from the viewer.
- Back, export, and delete stay as thin overlays. Zoom, swipe, playback, encryption, and package ID are unchanged.

## 1.2.5 (version code 10)

- Premium visual refresh: warmer ink surfaces, jewel mint accent, hairline cards, outlined secondary buttons, and circular back control.
- Lock, dashboard, media, documents, player overlay, and settings inherit the new design system. Feature logic, encryption, package ID, and signing are unchanged.
- Screenshot protection remains off for QA.

## 1.2.4 (version code 9)

- Settings now shows the real installed app version instead of a hardcoded 1.0.0.
- Player overlay is compact: seek and times share one row, Fit/Zoom/Fill sits with the transport buttons.
- Video controls auto-hide after a few seconds in portrait and landscape; tap the video to bring them back.
- Tall/portrait videos stay on Fit by default so faces are not cropped in landscape Zoom.
- Long photo and media names ellipsize in the middle instead of wrapping through the file extension.
- Duplicate pinch-zoom hint removed from the image viewer. Screenshot protection remains off for QA.

## 1.2.3 (version code 8)

- Temporarily disabled FLAG_SECURE screenshot blocking so device screenshots can be captured for QA. Set `BaseActivity.BLOCK_SCREENSHOTS` back to true before the final release.
- Encrypted audio/video player now autoplays, uses a faster start buffer, and shows a buffering spinner.
- Player controls sit as an overlay on the media. Tap play/pause, double-tap left/right to skip 10s, horizontal swipe to seek, left-half vertical swipe for brightness, right-half vertical swipe for volume.
- Speed options include 0.75×. Encryption, package ID, signing, and permissions are unchanged.

## 1.2.2 (version code 7)

- Encrypted photos can be pinched to zoom, dragged to pan, double-tapped to zoom, and slid left/right to the next photo.
- Encrypted documents now open inside the vault: PDF pages, text files, and Office/OpenDocument text previews.
- PDF viewing uses an in-memory memfd + Android PdfRenderer. No plaintext document file is written to disk, and files are still not handed to external apps.
- Document pages can be pinched to zoom and slid to the next page. Text previews support pinch-to-resize.
- Export and delete remain available. Package ID, signing, encryption format, and permissions are unchanged.

## 1.2.1 (version code 5)

- Removed the fullscreen landscape bottom gap by suppressing retained system-gesture padding while immersive video is active.
