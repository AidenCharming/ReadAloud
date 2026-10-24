# Licences and what to check before publishing

This is a working note, not legal advice. Anything marked CHECK should be confirmed before the app goes on Google Play.

## What ships in the app

| Component | Licence | What is needed |
|---|---|---|
| sherpa-onnx (speech runtime, `app/libs/sherpa-onnx-1.13.8.aar`) | Apache 2.0 | Credit (done in the About screen) |
| ONNX Runtime (inside that AAR) | MIT | Credit (done) |
| Kokoro-82M voice model (downloaded on demand) | Apache 2.0 | Credit (done) |
| Piper voice, English US "libritts_r" (downloaded on demand) | Trained on LibriTTS-R, CC BY 4.0 | Attribution (done) |
| eSpeak NG phoneme data and code | **GPL-3.0-or-later** | **CHECK, see below** |
| PDFBox-Android, Apache Commons Compress | Apache 2.0 | Credit (done) |
| jsoup | MIT | Credit (done) |
| Google ML Kit (text recognition, barcode and document scanning) | Google's ML Kit terms | Follow the Play data-safety guidance in PLAY_LISTING.md |
| Open Library (book details and covers) | Metadata is open data; cover images have their own terms | Credit (done). CHECK the cover terms if the app is ever commercial |

## The one real question: eSpeak NG is GPL

Both voices turn words into sounds with eSpeak NG. sherpa-onnx builds eSpeak NG into its native library
(`libsherpa-onnx-jni.so`) and the app ships eSpeak NG's `espeak-ng-data`. eSpeak NG is licensed GPL-3.0-or-later,
which is a copyleft licence: distributing a program that includes or links GPL code generally means the whole program
has to be offered under the GPL, with its source code.

Ways to deal with it, in rough order of effort:

1. **Publish the app's source under the GPL-3.0** (for example on GitHub) and link to it from the Play listing. This
   satisfies the licence and is common for free apps. It does not stop the app being free on Play.
2. **Ask a lawyer, or ask the sherpa-onnx maintainers**, how they treat apps that ship their AAR. They may have a view
   on which build flavours avoid eSpeak NG.
3. **Ship without the downloadable voices** (system voice only) and keep the rest of the app closed. This removes the
   question but also the main feature.

**Decision (Oct 23, 2026): option 1.** The source is published under GPL-3.0-or-later (`LICENSE` in the project root) and
the app is sold on Google Play as a paid release. The GPL allows charging; what it requires is that every copy comes with
the source (or an offer of it) and the same licence, so the paid app must link to the public repository (set `SOURCE_URL`
in `AboutDialog.kt`). Buyers and anyone else may build, share and change the app under the GPL.

Before the first release:
* Create the public repository and put its address in `SOURCE_URL` and in the Play listing.
* Keep `keystore.properties`, any `.jks` and `HANDOFF.md` out of it (already in `.gitignore`).
* Check that the Open Library terms allow use in a paid app, and that every dependency's licence is compatible with GPL-3.0.
* Consider a short review by an open-source licensing lawyer; this note is not legal advice.

## Other notes

* The Kokoro v1.0 package was chosen over v1.1 on purpose: v1.1 is a Chinese-focused model.
* The voice downloads come from the `k2-fsa/sherpa-onnx` GitHub releases page. If those files move or change, the
  app's SHA-256 check (in `KokoroModel.kt` and `PiperModel.kt`) will refuse them, which is the intended behaviour.
  Hosting a copy on a server you control removes that dependency.
* Open Library asks API users to identify themselves. The app sends a `User-Agent` of `ReadAloud/0.1`; put a contact
  address in it before release.
* `app/src/main/assets/words.txt` is the 20,000-word frequency list from the `first20hours/google-10000-english` GitHub
  project (MIT-licensed repository; word frequencies derived from Google's Trillion Word Corpus). It drives the scan
  spelling repair in `data/SpellFix.kt`. Confirm the licence terms in that repository before release.
