# Live Translator Mobile

Native Kotlin Android MVP (`0.1.0`) for translating audio from one selected, launcher-visible application into Ukrainian.

## Use

1. Open **Налаштування**, enter a Gemini Developer API key, and save it.
2. Optionally use **Перевірити з’єднання**. This is setup-only: it opens and closes a Live session and does not request capture permission or touch `AudioRecord`/`AudioTrack`.
3. On the main screen, refresh and select the source application. The picker is intentionally persistent and only lists applications visible through the narrow launcher query; it does not promise every installed package.
4. Tap **Почати переклад**, grant microphone permission for playback capture, then grant Android MediaProjection consent. Only after both grants does capture begin.
5. Stop from the app or the foreground notification. Stop cancels the session, discards queued translated audio, releases projection/capture/playback resources and does not restart without another explicit Start.

## Privacy and service limits

- The API key is stored as plaintext in app-private `filesDir/settings.json`; it is schema-validated, atomically replaced and never intentionally logged or backed up by this app. Do not share it.
- After Start and consent, audio from the selected application is streamed to Google for live translation. Captured audio is kept only in bounded memory; no recording files are written.
- Google data processing terms and free-tier limits may apply. This app does not enable billing and does not guarantee quota, model availability or long-running access.
- Original-volume separation or mixing is not promised. Capture eligibility and silence behavior vary by target application, Android build and device.
- Diagnostics use the stable `LiveTranslator` tag and lifecycle/level categories only; they do not include keys, URLs, server payloads, settings JSON or audio bytes.

## Protocol boundary

The implementation preserves the desktop repository's confirmed setup/model fixture, 16 kHz little-endian PCM packets of exactly 1600 samples, and 24 kHz little-endian output. Error categories remain distinct: credentials (400/401/403), quota (429), service unavailable (5xx and relevant close codes), protocol, network and ordinary close. The model fixture still requires an authorized service check; source inspection is not a current availability claim.

## Local verification

With an installed Android SDK and compatible JDK:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \\
  gradle --offline --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Device, Google service and target-application compatibility remain pending authorized validation; this repository does not claim real-device success.
