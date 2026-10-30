# connect-example-app

A minimal, standalone **Gradle** Android app showing how a third-party integrator drives in-person
(POS) card payments on a Rootline terminal — using the **Rootline Connect SDK**, published on Maven
Central as `com.rootline.connect:connect-sdk`.

It is the Gradle-buildable twin of the internal Bazel reference app: the same UI and integration, but
consuming the SDK as a normal Maven dependency so you can clone, open in Android Studio, and build it
without the Rootline monorepo.

The repository has two Gradle modules:

| Module | What it is |
| --- | --- |
| `app/` | The example integrator app, plus its unit and instrumented tests |
| `connect-app-emulator/` | A stand-in for the Rootline Connect app, to run the example on a standard Android emulator without a terminal — see [Testing without a terminal](#testing-without-a-terminal-the-connect-app-emulator) |

`scripts/` holds helpers that start an Android emulator with the connect-app emulator installed.

## What it does

- **Start connect** — boots the Connect runtime via `RootlineConnect.start()`.
- **Request payment (€12.99)** — creates a card-present payment with `POST /v1/payments` on the
  Payment API, then renders the outcome the connect-app reports back over the SDK
  (succeeded / failed / cancelled).
- A live **NATS connectivity** indicator and a **runtime status** line.
- **Reprint receipts** — for a succeeded payment, print the customer or merchant receipt via
  `RootlineConnect.printReceipt(...)`.

### Two halves: initiate over HTTP, receive over the SDK

Payments are **initiated** over the Payment API rather than through the SDK, because
`payment_rails.payment_device_id` is what tells the backend to route the payment to a specific terminal
as a card-present prompt instead of hosted checkout.

Everything **coming back** still arrives through the SDK — connection state, runtime state, payment
outcomes, and print results — so the terminal remains the source of truth for what actually happened.

Integration lives in three files:

| File | Role |
| --- | --- |
| `MainActivity.kt` | Constructs `RootlineConnect`, registers the handlers, calls `start()` |
| `PaymentApi.kt` | The `POST /v1/payments` call that starts a payment on the terminal |
| `ConnectClient.kt` | A pure, unit-tested reducer folding the SDK's typed results into UI state |

## Payment API configuration

The app needs three values that are specific to your integration. They are read from
`local.properties`, which is **gitignored** — no key, account id, or terminal id belongs in the repo.

Add them to `local.properties` in the project root:

```properties
paymentApiKey=sk_test_your_secret_key_here
paymentAccountId=acc_your_account_id
paymentDeviceId=device_your_payment_device_id
```

| Property | Where it comes from |
| --- | --- |
| `paymentApiKey` | Your Payment API secret key. Sent as the `X-Api-Key` header. **Secret** — never commit it. |
| `paymentAccountId` | The account the payment is created on. One namespace-scoped key can serve several accounts. |
| `paymentDeviceId` | The terminal to charge on, sent as `payment_rails.payment_device_id`. Provisioned per terminal. |

`app/build.gradle.kts` reads these and exposes them through `BuildConfig`. The values committed here
are **placeholders** (`acc_YOUR_ACCOUNT_ID`, `device_YOUR_PAYMENT_DEVICE_ID`), so a fresh clone builds
without any setup — but payments will fail until you supply real ones. A missing `paymentApiKey` is
reported in the UI rather than sent as an unauthenticated request.

The example targets the sandbox host (`https://payment-api.sandbox.rootline.com`); change
`PAYMENT_API_URL` in `PaymentApi.kt` for other environments.

## Using the SDK

Add Maven Central and the single dependency (already wired up here):

```kotlin
// settings.gradle.kts → dependencyResolutionManagement { repositories { google(); mavenCentral() } }

// app/build.gradle.kts
dependencies {
    implementation("com.rootline.connect:connect-sdk:1.0.3")
}
```

The SDK's POM transitively brings kotlin-stdlib, kotlinx-coroutines, androidx.activity/core/lifecycle,
and the bundled wire/moshi transport, so the app itself only adds the Compose UI dependencies.

Minimal usage:

```kotlin
val connect = RootlineConnect(this)          // this = a ComponentActivity
    .onRuntimeStarted  { /* it.success / it.reason */ }
    .onConnectionState { /* it.connected */ }
    .onPaymentState    { /* it.id, it.outcome, it.amountMinor, it.clientId */ }
    .onPrintResult     { /* it.outcome */ }
    .start()

connect.printReceipt(paymentId, ReceiptRecipient.CUSTOMER)
```

## Build & run

```bash
./gradlew :app:testDebugUnitTest                  # run the pure-JVM reducer tests
./gradlew :app:assembleDebug                      # build app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug                       # install onto a connected device/emulator
./gradlew :connect-app-emulator:installDebug      # install the connect-app emulator (emulators only)
./gradlew :app:connectedDebugAndroidTest          # run the instrumented tests against it
```

Requirements: an Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`) and **JDK 17–21**.
The bundled Gradle 8.14.3 cannot parse newer JDK version strings and fails with
`IllegalArgumentException` on JDK 26, so pin your JDK if your default is newer. The project targets
`minSdk 26`, `compileSdk 36`.

## Runtime requirement

For live connectivity and payments, the **Rootline Connect app (`com.rootline.connect`) must be
installed** on the device/terminal — this example drives it over the SDK. Without it, the app still
builds and launches, but the connection stays disconnected and no payments arrive (the SDK degrades
gracefully). Without a terminal, use the [connect-app emulator](#testing-without-a-terminal-the-connect-app-emulator)
on an Android emulator instead.

Several things are provisioned server-side per terminal, and each one fails quietly if it is missing.
If the app runs but nothing happens, check the connect-app's logcat for:

| Symptom in logcat | Meaning |
| --- | --- |
| `Rejecting reply package: not in the integrator allowlist` | Your package is not allow-listed as an integrator on that terminal. |
| `Rejecting outbox access from <your package>` | Same allow-listing, for the durable outcome outbox. |
| `requestPayment ignored: no payment template configured` | The terminal has no payment template provisioned. |
| `presentation timeout: not presented within 10s` | The payment UI was not presented in time — see below. |

### Presentation is integrator-driven

The connect-app expects the integrator to bring the payment UI to the front, and fails the payment if
that has not happened within 10 seconds (`Not foreground; awaiting integrator-driven presentation`).
An app that is backgrounded — or cold-starting — can lose that race, so a payment started while your
activity is not resumed may time out. Keep the integrator activity in the foreground when starting a
payment.

## Testing without a terminal: the connect-app emulator

`connect-app-emulator/` builds a second APK: a stand-in for the Rootline Connect app that runs on a
standard Android emulator. It answers the SDK like the real connect-app does, but has no card
reader, no printer and no backend. Use it to develop and test your integration before you have a
terminal.

> **Install it only on an emulator.** It uses the real package name, `com.rootline.connect`, because
> the SDK addresses the connect-app by package name. It therefore cannot be installed next to the
> real connect-app, and a terminal with the real connect-app rejects it.

What it does:

- `start()`: reports the runtime as started and the connection as connected.
- **Simulated incoming payment**: does what the backend does on a terminal. It writes the present
  trigger, the SDK opens the simulated card screen, and the outcome arrives on `onPaymentState`.
  The payment fails if your app does not present it within 10 seconds, as on a terminal.
- `requestPayment(...)`: opens a payment at once. The amount and currency must be set, otherwise the
  outcome is `failed`.
- `printReceipt(...)`: nothing prints. The result is the next value from the print-result queue, or
  `OK`. A payment that did not succeed gets `NOT_FOUND`.

Choose outcomes in the emulator's own screen (the "Connect Emulator" launcher icon), or with adb:

```bash
E="adb shell am broadcast -p com.rootline.connect -a com.rootline.connect.emulator.action"
$E.SIMULATE_PAYMENT --es outcome succeeded --el amount_minor 1299 --es currency EUR  # or failed, cancelled, manual
$E.SET_NEXT_OUTCOME --es outcome manual          # outcome for requestPayment and the screen button
$E.SET_PRINT_RESULTS --es results OK,OUT_OF_PAPER  # results of the next prints, in order
$E.SET_CONNECTED --ez connected false            # send a "disconnected" connection state
$E.RESET                                         # clear payments, outbox, settings and integrator
```

With `outcome manual`, the card screen waits for a tap on Approve, Decline or Cancel.

### Instrumented tests

`app/src/androidTest/.../ConnectEmulatorTest.kt` drives the example app against the emulator: start,
a succeeded, failed and cancelled payment, a printer that is out of paper, and a disconnect. It
controls the emulator with the same `am broadcast` commands. On any emulator (API 26 or later):

```bash
./gradlew :connect-app-emulator:installDebug
./gradlew :app:connectedDebugAndroidTest
```

These tests do not call the Payment API, so they need no `local.properties` values. The
`.github/workflows/android-tests.yml` workflow runs them on each push and pull request.

`connectedDebugAndroidTest` runs on every connected device. Set `ANDROID_SERIAL` to choose one.

### A local emulator, without Docker (macOS and Linux)

`scripts/start-emulator.sh` creates and boots an API 34 emulator for this machine, and installs the
connect-app emulator. It picks the arm64-v8a system image on Apple Silicon and x86_64 on Intel and
Linux. The emulator uses the macOS hypervisor on a Mac and KVM on Linux. It uses the Android SDK
of Android Studio, and installs the command-line tools, the emulator and the system image into it
when they are missing.

```bash
scripts/start-emulator.sh               # or --headless, --api 35, --variant google_apis, --apk <path>
ANDROID_SERIAL=emulator-5584 ./gradlew :app:connectedDebugAndroidTest
scripts/start-emulator.sh --stop
```

### A ready-to-use emulator image (Linux only)

`scripts/build-emulator-image.sh` builds a Docker image with a headless API 34 emulator and the
connect-app emulator installed. It is the setup Rootline runs its own emulator tests on, including
the pinned `maestro-runner` for UI flows. It needs Linux with KVM and Docker. It does not work on
macOS: Docker Desktop runs containers in a Linux VM that has no KVM. On a Mac, use
`scripts/start-emulator.sh`.

```bash
scripts/build-emulator-image.sh                    # or --api 35, --variant google_apis, --apk <path>
docker run -d --name connect-emulator --device /dev/kvm --shm-size=2g -p 15555:5556 \
    connect-app-emulator:api-34-aosp_atd-x86_64
# wait until `docker ps` shows the container as healthy (about a minute)
adb connect localhost:15555
./gradlew :app:connectedDebugAndroidTest
```

### What the emulator does not cover

The emulator checks how your app uses the SDK. It does not check:

- real cards, card schemes, declines from a bank, or receipts on paper;
- the Payment API (`POST /v1/payments`) and the routing of a payment to a terminal;
- the provisioning per terminal: the integrator allowlist and the payment template.

Test those on a sandbox terminal before you go live.

## Docs

SDK guide: https://docs.rootline.com/guides/in-person-payments/connect-sdk

## License

[MIT](LICENSE)
