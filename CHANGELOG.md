# Changelog

## 1.4.4 (version code 21)

- Files tab: type folders (Images, Videos, Audio, PDF, Text, Documents, Other). Each file stays only in its folder.
- Video seek overlay uses plain `+0:05 / 0:26` (no broken arrow glyph). Seek no longer freezes the progress UI.
- List rows recycle views to cut lag while scrolling. Navigation back from a folder returns to type folders first.
- Encryption, package ID, and signing are unchanged. Over-install on 1.4.3.


## 1.4.3 (version code 20)

- Backup import now validates the wrapped key and writes atomically, so a bad file cannot wipe a working vault.
- Zip restore rejects path traversal and oversized archives. Destroy vault also wipes Keystore aliases.
- File detail is a full screen (Open / Download / Export .enc). Encryption unchanged. Over-install on 1.4.2.

## 1.4.2 (version code 19)

- Brand: launcher name **Aegis**, subtitle Private vault, steel-and-gold shield icon.
- Lock and setup screens show the Aegis mark. Package ID unchanged for over-install.
- Encryption is unchanged (AES-256-GCM / GVM2). Over-install on 1.4.1 / 1.4.0.

## 1.4.1 (version code 18)

- Login / note / card tap opens a detail screen (copy, star, edit, delete) matching the vault screenshots.
- List rows show icon, username · category · relative time, and a category pill.
- Generator: length chips (12/16/20/24) and Uppercase / Lowercase / Digits / Symbols.
- File detail: Open, Download, Export .enc. Settings destroy is solid coral.
- Encryption is unchanged. Over-install on 1.4.0.

## 1.4.0 (version code 17)

- Overview now matches the Aegis-style home: greeting, Generate, health cards, Recent, Favorites, and Start with one secret.
- Hamburger drawer with counts, Generator, Settings, Lock, and theme toggle.
- Files: title, category, favorite, tags, notes. Encrypt-file form. Download or Export .enc.
- Settings backup: export `.gpv`, import backup, import `.enc`.
- Encryption is unchanged. Over-install on 1.3.x.

## 1.3.3 (version code 16)

- Overview counts now show real totals (Files was stuck at 0).
- Bottom nav stays pinned to the bottom on Overview.
- Back from Passwords / Notes / Cards / Files returns to Overview instead of leaving the app.
