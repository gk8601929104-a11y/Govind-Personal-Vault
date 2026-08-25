# Tablet-only GitHub update guide — Govind Personal Vault 1.2.5

The public source ZIP contains no keystore or signing passwords. Keep the private signing kit offline.

## Replace only the source ZIP

1. Open the `Govind-Personal-Vault` repository in Chrome.
2. In the repository root, delete the previous `Govind_Personal_Vault_GitHub_Public_Repo_v*.zip`.
3. Upload the supplied v1.2.5 public source ZIP and commit it.
4. Confirm that exactly one matching public source ZIP remains.
5. Do **not** edit `.github/workflows/build-apk.yml` or the four repository secrets.

## Build and install the update

1. Open **Actions** → **Build signed APK**.
2. Tap **Run workflow**, keep branch `main`, and run it.
3. Wait for a green **Success** result.
4. Download artifact `Govind-Personal-Vault-v1.2.5`.
5. Extract the artifact ZIP and install `Govind_Personal_Vault_v1.2.5.apk`.
6. Choose **Update**. Do not uninstall the existing Vault app, because uninstalling removes local app data.

## If the build fails

Open the red `build` job and share the first real error around `What went wrong`, `Error:`, `FAIL:`,
or the first `> Task ... FAILED` line. Never share repository secret values.
