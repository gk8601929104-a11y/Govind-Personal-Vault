# Govind Personal Vault

Govind Personal Vault is an offline Android 12+ application for passwords, secure notes, encrypted private media, and encrypted documents. It uses a 100% programmatic Android UI: there are no layout XML files.

## Current release: 1.4.4 (versionCode 21)

### Version 1.4.4 — Type folders + smooth seek

- Files tab opens type folders first: Images, Videos, Audio, PDF, Text, Documents, Other. Each file lives only in its folder. Back returns to the folder list.
- Video seek overlay uses plain `+0:05 / 0:26` (no broken Unicode arrow). Seek no longer freezes the progress UI.
- List rows recycle views to cut scroll lag. Navigation and tab switches are smoother.
- Package ID, GVM2, AES-256-GCM, and signing are unchanged. Over-install on 1.4.3.

### Version 1.4.3 — Hardened restore

- Backup import validates the wrapped key first and writes in one pass.
- Zip restore blocks path traversal and oversized archives. Destroy also wipes Keystore keys.
- Package ID, GVM2, AES-256-GCM, and signing are unchanged. Over-install on 1.4.2.

### Version 1.4.2 — Aegis mark

- App name is **Aegis**. Private vault. Launcher icon is the steel-and-gold shield.
- Package ID, GVM2, AES-256-GCM, and signing are unchanged. Over-install on 1.4.1.


- Tap a login, note, or card for a detail screen with copy, reveal, header Edit, star, and delete.
- Generator length chips and character-class checkboxes. File detail: Open, Download, Export .enc.
- Encryption, package ID, GVM2 viewers, and signing are unchanged. Over-install on 1.4.0.

### Version 1.4.0 — Aegis-style vault

- Home: greeting, New login / Generate, count tiles, weak / reused / favorites health, Recent, Favorites.
- Drawer: Overview, Passwords, Notes, Cards, Files, Generator, Settings, Lock.
- Files carry title, category, favorite, tags, and notes. Encrypt-file form. Download plaintext or export `.enc` ciphertext.
- Settings backup: export `.gpv` (wrapped key + ciphertext DB + `.gvm`), import backup, import `.enc`.
- Generator creates a strong password on-device and can drop it into a new login.
- Encryption, package ID, GVM2 viewers, and signing are unchanged. Over-install on 1.3.x.

## Previous release: 1.3.3 (versionCode 16)

### Version 1.3.3 — Overview counts, pinned nav, back

- Homepage file counts match the Files tab. Bottom bar stays at the bottom.
- Android back from any tab returns to Overview.

- Overview dashboard with tappable counts. Passwords title stays on one line.
- Tap a document or photo to hide tools instantly; status bar hides while the file is open.
- Video seek is smooth: drag shows time, release seeks once.

### Version 1.3.0 — Aegis-inspired shell

- Bottom navigation: Overview, Passwords, Notes, Cards, Files.
- Dark/Light, auto-lock while idle, clipboard timing, destroy vault.
- Cards and category chips. Existing encrypted files still open in the secure viewers.

### Version 1.2.7 — Cleaner chrome
