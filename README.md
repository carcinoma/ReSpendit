<p align="center">
  <img src="docs/logo.svg" alt="ReSpendit logo" width="456">
</p>

<h1 align="center">ReSpendit</h1>

<p align="center">
  <a href="https://github.com/OWNER/REPO/actions/workflows/build.yml"><img src="https://github.com/OWNER/REPO/actions/workflows/build.yml/badge.svg" alt="Build status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License: Apache 2.0"></a>
</p>

<p align="center">A lean, tracking-free Android client for the SpenditCard.</p>

> **Unofficial.** ReSpendit is a private, independent project and is not affiliated with or endorsed
> by spendit AG or Solaris SE. "spendit" is a trademark of its owner and is used here only to
> identify the service the app talks to.

## Why ReSpendit

- **No tracking at all:** no Firebase Analytics, Crashlytics or Messaging, no Grafana Faro, no
  Google Maps, no advertising ID. The app talks to exactly one host, `app.api.spendit.de`.
- **Small:** a 4 MB APK instead of a 40 MB bundle.
- **Two permissions:** `INTERNET` and `USE_BIOMETRIC`. No location, camera, contacts or
  notifications.
- **Everything the card needs:** registration, login, card setup, PIN, block and unblock,
  transactions with balance, monthly statements, device management, and approving or declining
  online payments and suspicious transactions.
- **Stricter security than the original:**
  - session tokens encrypted with a hardware-backed key instead of stored in plain text;
  - the PIN-signing key is unusable without biometrics or the screen lock;
  - online payments need a biometric or screen-lock confirmation;
  - screenshots and backups are blocked.
- **Privacy-preserving diagnostics:** a built-in log shows every request but replaces passwords,
  TANs, PINs, tokens and signatures with placeholders such as `<str 6 digits>`.
- **Reproducible:** plain Kotlin and Jetpack Compose, unit-tested, built by GitHub Actions.

## How it was built

1. **Static analysis of the official app.** The APK bundle (`de.spendit.one` 1.45.0) was unpacked.
   - Manifest and resources: decoded with androguard.
   - Java/Kotlin layer: decompiled with jadx.
   - Flutter/Dart layer: `libapp.so` decompiled with blutter into annotated arm64 assembly.

   The result was a report on what the app sends where, and a split of its components into
   card-essential and removable.
2. **Rewrite instead of patch.** The Dart code is AOT-compiled machine code and can't be
   recompiled, so stripping it down in place wasn't possible. ReSpendit is a new native app that
   speaks the same protocol.
3. **Protocol specification.** From the decompiled code, written down in four parts:
   - endpoints and HTTP layer;
   - JSON models;
   - Keystore keys and cryptography: EC P-256 device keys, hex-DER signatures, and a JWE
     RSA-OAEP-256 / A256CBC-HS512 PIN;
   - flow logic.

   Every point where these sources disagreed was settled by reading the disassembly directly.
4. **Implementation.** Kotlin, Jetpack Compose, OkHttp, kotlinx.serialization and Nimbus JOSE.
5. **Review and tests.**
   - An independent code review against the spec.
   - Unit tests for wire formats and crypto.
   - A Robolectric test that starts the real app and takes screenshots.
   - A mock-server test for the HTTP headers.
   - An opt-in smoke test against the live backend using only harmless requests.
6. **Field test.** Card setup on a real phone, with problems diagnosed from the in-app log and
   fixed in short iterations (for example, the server returns 404 until a phone number exists).

The analysis, the rewrite and the testing were done with the help of Claude Code (Anthropic).

## What it does

| Area | Functions |
|---|---|
| Account | log in / log out, choose employer (several `appUsers`), register with employer code, activate the account (paste the e-mail link), forgot / reset password (paste the link) |
| Card | list cards (status, holder, masked PAN, expiry, region), set up a card (phone → device binding → PIN), activate, activate with new PIN, change PIN, block / unblock, change region (PLZ), transactions, monthly statements (PDF) |
| Device binding | bind this phone (SMS TAN), list / remove bound devices |
| Phone number | add, change (removes the old number first) |
| Payment authorization | approve / decline online payments (SCA), with device signature or SMS TAN; answer Solaris fraud cases (release for 10 min / confirm fraud and block) |
| Notifications | notification centre list, mark as read |

Server-sent quick actions are shown only when the app supports them. Unknown ones are hidden.

## What was removed

Firebase (Analytics, Crashlytics, Messaging), Grafana Faro, Google Maps / location, deals and
raffles, receipts / Lunchit / D-Ticket / OCR uploads, home feed and banners, Apple Pay / Wallet,
onboarding and how-to content, feedback, WebViews and deep links, in-app review, root detection,
OTP autofill, and the media/UI libraries.

The release APK is about 4 MB, against 40 MB for the original bundle. Its permissions are
`INTERNET` and `USE_BIOMETRIC`. The second also implies `USE_FINGERPRINT`, and androidx adds an
app-private permission for its own receivers. The app talks only to `https://app.api.spendit.de`.

## Differences from the original (deliberate)

- **Its own keys mean a new device binding.** Android Keystore keys belong to the app that created
  them, so the official app's keys cannot be used. The first time you use this app you bind it as
  an additional device (SMS TAN). spendit allows at most 5 bound devices. Remove old ones under
  Geräteverwaltung (device management).
- **No push.** Pending payment approvals and fraud cases are fetched when the app opens and then
  every 15 s while it is in the foreground. The original also shows them only after
  open/resume. Without push you have to open the app when a payment needs approval.
- **Stronger local security.** These were flagged in REPORT §6 and §10.4.
  - Session tokens are encrypted with a Keystore AES key (the original keeps them in plain
    SharedPreferences).
  - The restricted key is created with `setUserAuthenticationRequired` (biometric or screen lock
    for every PIN change or phone-number removal). The original only shows a cosmetic prompt.
  - Approving an online payment asks for biometrics or the screen lock (the original asks for
    nothing).
  - `FLAG_SECURE` is set and backups are disabled.
- If the restricted key gets invalidated (for example, the screen lock was removed and set up
  again), the app creates a new one and registers it again with `POST /device/{id}/key`, like the
  original's legacy-key path.
- Biometric *login* (stored password) is not included. You log in with e-mail and password.
- The region PLZ is checked only for being 5 digits. The original checks it against a built-in
  list of 16k postcodes, and the server makes the final decision either way.

## Diagnostic log

Menu ☰ → **Über** → **Diagnose-Log** (on the login screen: **Über ReSpendit** → **Diagnose-Log**). It records:
- every request: method, path, HTTP status, duration, request body and response body;
- screen changes, key generation and biometric prompt results;
- errors, including crashes (the stack trace is written before the app dies).

Secrets are replaced by their shape, for example `"tan":"<str 6 digits>"` or
`"publicKey":"<str 130 hex>"`. That's enough to spot a format error without exposing the value.
- Only status-like keys keep their values (`status`, `type`, `quickActionType`, `verified`, dates, …).
- IDs in paths become `{u}`, `{c}` and `{d}`.
- `/login` and `/refreshToken` bodies are never logged.
- The log stays in the app's private storage (max. 256 KB). It leaves the phone only if you tap
  **Kopieren** (copy) or **Teilen** (share).

To report a problem, reproduce it, copy the log and include it in the bug report together with a
short description of what you did.

## Build

```sh
. ./env.sh                 # JDK 17, Android SDK, Gradle from ../toolchain
./gradlew testDebugUnitTest assembleRelease
# → app/build/outputs/apk/release/app-release.apk (signed with signing/release.jks)
```

`signing/` holds a locally generated release keystore and its password
(`signing/keystore.properties`). Keep it: updates must be signed with the same key, otherwise
Android refuses the update and the bound keys are lost when you reinstall.

Install with `adb install app/build/outputs/apk/release/app-release.apk`. Package `local.cardmin`,
minSdk 30 (Android 11). A screen lock must be set.

## GitHub Actions

`.github/workflows/build.yml` runs on pushes to `main`, on pull requests, on tags `v*` and on
manual dispatch. It runs the unit tests (the online test stays skipped), builds debug and release
APKs, and uploads them together with the test reports and Robolectric screenshots as artifacts.
For a `v*` tag it also attaches the release APK to a GitHub release.

Release signing is optional. Without secrets the release APK is unsigned (`app-release-unsigned.apk`).
To produce APKs that install as updates over your phone's copy, the CI must use the **same** key as
`signing/release.jks`. Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 signing/release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | `storePassword` from `signing/keystore.properties` |
| `RELEASE_KEY_ALIAS` | `keyAlias` (`cardmin`) |
| `RELEASE_KEY_PASSWORD` | `keyPassword` |

`signing/` itself is in `.gitignore` and must never be committed.

## Status

Tested on a real phone (Android 15) against the live backend: registration, login, phone number,
device binding, PIN setup (the card switched to `ACTIVE`) and transactions all work. Not yet seen
live: online-payment approval (SCA), fraud-case answers and the balance box.

Open points from the static analysis:

1. Whether the server accepts a re-registered restricted key for an existing device.
2. Which setup path calls `/activate` before `PUT /pin`. This app calls it only for the
   `ACTIVATE_CARD_NEW_PIN` quick action, as the original does for its third PIN-screen mode.
3. Error codes outside `ErrorType` are shown as plain HTTP status.

## Layout

```
app/src/main/java/local/cardmin/
  Config.kt              base URL, headers, constants
  data/Api.kt            OkHttp client: all endpoints, headers, traceparent, 401 → /refreshToken
  data/Models.kt         request/response JSON (kotlinx.serialization)
  data/SecureStore.kt    encrypted token / deviceId storage
  crypto/DeviceKeys.kt   Keystore EC P-256 device + restricted key, hex point / hex DER signatures
  crypto/PinCrypto.kt    JWE RSA-OAEP-256 / A256CBC-HS512 PIN encryption (Nimbus JOSE)
  ui/AppViewModel.kt     all flows and navigation
  ui/Screens.kt          Compose UI (German)
  MainActivity.kt        BiometricPrompt host, PDF viewer intent, foreground polling
```

## License

ReSpendit is licensed under the [Apache License, Version 2.0](LICENSE). This includes the
logo (`docs/logo.svg` and the app's icon and logo resources). See [NOTICE](NOTICE) for attribution.
As section 6 of the license states, it grants no rights to trademarks such as the spendit name.
