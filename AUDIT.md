# Govind Personal Vault 1.2.1 — Security and UI/UX Audit

## Preserved security boundaries

- No Internet permission, ads, analytics, telemetry, or cloud sync.
- Backup/device-transfer extraction and cleartext traffic remain disabled.
- All non-launcher activities are non-exported.
- No broad media or storage permission is requested.
- `FLAG_SECURE` remains active before sensitive UI is rendered.
- Package ID, signing model, database schema, and GVM2 encrypted-file format are unchanged.

## Landscape dashboard

The dashboard uses one `ListView` with programmatic header and footer content. This makes stats,
categories, search, list rows, empty state, and the add action reachable by vertical scrolling without
nesting a `ListView` inside a `ScrollView`.

## Immersive encrypted-media player

- Media3 `PlayerView` is created programmatically and its built-in controller is disabled.
- Its `SurfaceView` is marked secure before attachment.
- The custom `EncryptedCipherDataSource` continues authenticated on-the-fly decryption.
- Landscape video hides system bars and app title/metadata chrome.
- Retained system-gesture bottom padding is removed only while immersive video is active.
- Zoom is the automatic landscape default to fill tablet canvases; Fit and Fill remain selectable.
- Playback controls auto-hide only while playing and reappear on tap.
- Left-half vertical swipe changes per-window brightness; right-half vertical swipe changes STREAM_MUSIC volume.
- No WRITE_SETTINGS permission or global brightness write is used.
- Fit, Zoom, and Fill modes use Media3 resize constants.
- Audio remains in a normal non-immersive UI.

## Encrypted Documents Vault

- `OpenMultipleDocuments` is used through the system Storage Access Framework.
- Provider metadata is sanitized and MIME type is validated.
- Image/video/audio MIME types are rejected by Documents and remain in Media Vault.
- Non-media documents reuse bounded GVM2 AES-256-GCM encryption, epoch checks, internal storage,
  crash reconciliation, storage preflight, and encrypted SQLite metadata.
- Export writes directly to a pending `MediaStore.Downloads` row and publishes only after success.
- Cancelled or failed exports remove the incomplete row.
- No plaintext temporary preview or external decrypted-file intent is created.

## Validation performed in this workspace

- Repository signing-material scan passed.
- Source security/UI/document audit passed.
- Unicode bidi-control scan passed.
- Shell syntax, XML parsing, and workflow YAML parsing passed.
- Java parser-stage structural scan found no syntax/truncation errors.
- ZIP integrity and source-hash checks are generated before release packaging.

## Requires GitHub/device confirmation

The authoritative Android compilation/signing check is the pinned GitHub Actions workflow. Device
validation must cover dashboard scrolling, immersive rotation, no bottom inset gap, resize modes, brightness/volume gestures, control auto-hide,
document import/export/delete, lock races, low storage, corruption, and update installation.

## Not claimed

- Protection after unlock on a rooted or malware-controlled device.
- Guaranteed decoding of every codec on every Android device.
- Secure deletion guarantees from flash wear levelling.
- Independent third-party cryptographic certification.
