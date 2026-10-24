# Google Play listing and release checklist

## Text

**App name:** ReadAloud: Listen to Books

**Short description (80 characters max):** Turn any book, PDF or scanned page into audio. Keep a shelf and lend to friends.

**Full description (draft):**
ReadAloud reads your books to you. Import a PDF, EPUB or text file, or photograph printed pages, and listen with a
natural voice that works offline, with the screen off, or from your lock screen.

* Natural on-device voices (Piper and Kokoro), downloaded once and then fully offline, or your phone's own voice
* Follow along with the sentence highlighted; tap any sentence to jump there
* Speed control, sleep timer and chapter jumping
* Scan printed pages and have them read aloud
* A bookshelf for the books you own: scan the barcode or just photograph the cover
* Lend books to friends, with due dates and reminders, so you remember who has what
* Private by design: your library stays on your phone. No account, no ads.

## Pricing and source code

* **Paid app.** Set the price in Play Console. A paid app needs a payments profile (a one-time registration fee applies
  to the developer account, and Google keeps a share of each sale). A free app cannot be changed to paid later, so
  publish as paid from the start.
* **Say the source is free.** In the full description add a line such as: "ReadAloud is free software. The source code
  is public at <repository address>; this version is the convenient, auto-updating build." It is honest and the GPL
  expects the source to be easy to find.
* Refunds, price changes and countries are set in Play Console.

## Data safety form (Play Console), suggested answers

| Question | Answer |
|---|---|
| Does the app collect or share user data? | No data is collected by the developer. Lookups go to Open Library; see the privacy policy. |
| Is data encrypted in transit? | Yes (HTTPS) |
| Can users request deletion? | Data is only on the device; uninstalling deletes it |
| Camera, microphone, location, contacts | Not accessed by the app (scanning uses Google's scanner UI) |

Check the answers again against the current Play wording before submitting, and re-check whenever a feature that uses
the network is added.

## Permissions to be ready to explain

* `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: keeps reading aloud when the screen is off.
* `POST_NOTIFICATIONS`: the player card and due-date reminders.
* `INTERNET` and `ACCESS_NETWORK_STATE`: book lookups, covers, voice downloads, and the mobile-data warning.
* `RECEIVE_BOOT_COMPLETED`: restores lending reminders after the phone restarts.

## Assets still to make

* 512x512 app icon (export from `res/drawable/ic_launcher_foreground.xml` on the `#1F4E54` background)
* 1024x500 feature graphic
* At least 2 phone screenshots (take 4 to 8: library, reader, shelf, lending, cover scan)
* Privacy policy URL (host `PRIVACY_POLICY.md`)
* Content rating questionnaire (expect "Everyone")

## Building the release

1. Make an upload key once and keep it somewhere safe and backed up (losing it means you cannot update the app):
   `keytool -genkeypair -v -keystore readaloud-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias readaloud`
2. Create `keystore.properties` in the project root (it is for you only; never commit it):
   ```
   storeFile=C:/path/to/readaloud-upload.jks
   storePassword=...
   keyAlias=readaloud
   keyPassword=...
   ```
3. Raise `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload.
4. `./gradlew.bat :app:bundleRelease`, then upload `app/build/outputs/bundle/release/app-release.aab` to Play Console.
5. Test the release APK (`:app:assembleRelease`) on a phone before uploading; the code shrinker is on and has to be
   proven not to break the voices.
6. Resolve the eSpeak NG question in `LICENSE_NOTES.md` first.
