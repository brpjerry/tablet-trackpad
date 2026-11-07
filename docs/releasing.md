# Releasing

Two GitHub Actions workflows build the app:

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request: unit
  tests, lint for both build types and a debug build. Test and lint reports and the debug APK are
  kept as workflow artifacts.
- **Release** (`.github/workflows/release.yml`) runs when a tag starting with `v` is pushed. It
  runs CI first, checks that the tag matches the app's `versionName`, builds the release APK signed
  with the release key, checks that the signature is that key's, and publishes a GitHub release
  with the APK, a `SHA256SUMS` file and generated notes.

## Cutting a release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName` to the new version,
   for example `0.2.0`. Android only installs an update with a higher `versionCode`.
2. Commit that to `main` and let CI pass.
3. Tag the commit and push the tag:

   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

The Release workflow does the rest. If it fails before publishing, fix the problem, then move the
tag to the fixed commit (`git tag -f v0.2.0 && git push -f origin v0.2.0`). Once a release is
published, ship fixes as a new version instead.

## The release key

Release APKs are signed with one key for the lifetime of the app: Android refuses to update an
installed copy with an APK signed by a different key, so losing the key means every user has to
uninstall (and lose their settings) to get later versions.

- The keystore is a PKCS12 file with one key, alias `tablet-trackpad`, RSA 4096, valid until 2054.
  Its certificate's SHA-256 fingerprint is
  `EE:32:E6:FA:3A:31:C3:D5:DD:54:3F:A0:4E:37:29:4B:87:62:F7:33:67:1F:CE:CC:E3:AC:46:CB:E8:B8:74:96`.
  The Release workflow refuses to publish an APK signed with anything else.
- The repository's Actions secrets hold it: `RELEASE_KEYSTORE_BASE64` (the keystore, base64) and
  `RELEASE_KEYSTORE_PASSWORD` (its password, which is also the key's). Secrets cannot be read back
  out of GitHub, so keep the original files backed up somewhere safe, such as a password manager.
- Never commit the keystore; `.gitignore` excludes `*.jks` and `*.keystore`.

To sign a release build locally, point Gradle at the keystore through the environment:

```sh
export RELEASE_KEYSTORE=/path/to/tablet-trackpad-release.jks
export RELEASE_KEY_ALIAS=tablet-trackpad
read -rs RELEASE_KEYSTORE_PASSWORD && export RELEASE_KEYSTORE_PASSWORD
export RELEASE_KEY_PASSWORD="$RELEASE_KEYSTORE_PASSWORD"
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

A release-signed APK cannot replace a debug build on a device, or the other way round, because
their keys differ: switching between them means uninstalling first, which clears the settings.

## Google Play

Play requires an Android App Bundle (`./gradlew :app:bundleRelease`) and signs what it distributes
itself. To keep Play and GitHub installs updatable from each other, give Play this same key as the
app signing key when enrolling in Play App Signing, rather than letting Play generate one.
