# ReadAloud

An Android app that reads books aloud. Import a PDF, EPUB or text file, or photograph printed pages, and listen with a
natural offline voice (Piper or Kokoro) or the phone's own voice. It also keeps a bookshelf of the books you own, with
barcode and cover scanning, and tracks books you lend to friends.

## Free to build, paid for convenience

ReadAloud is free software under the **GNU General Public License v3.0** (see `LICENSE`). You are welcome to build it
yourself, change it and share it under the same licence.

If you would rather not build it, the ready-made app on Google Play is a paid release: the price pays for the
convenience of installing it in one tap and for automatic updates.

## Building

You need Android Studio (or JDK 17+ and the Android SDK), then:

```
./gradlew :app:assembleDebug      # debug APK in app/build/outputs/apk/debug/
```

The speech runtime (`app/libs/sherpa-onnx-*.aar`) is included in the repository. The voice models are not: the app
downloads them on first use, checking each file against a SHA-256 hash.

A release build signs with the key described in a `keystore.properties` file that is not in the repository. Without it
the build falls back to the debug key, which is fine for your own use.

## Privacy

Everything stays on the phone. See `docs/PRIVACY_POLICY.md` for the short list of things that go online.

## Licences of what is inside

See `docs/LICENSE_NOTES.md` and the About screen in the app. In short: this app is GPL-3.0-or-later because it includes
eSpeak NG (GPL-3.0-or-later); Kokoro and sherpa-onnx are Apache-2.0; the Piper voice is CC BY 4.0.
