# Kozen P8 Neo printing integration

The Android POS label flow uses the installed `com.pos.sdk` shared library by reflection. It does not use Android's document printing service and does not bundle the vendor JAR. The API surface was inspected with `javap -constants` against the owner-provided `vendor-sdk.jar` and checked against the working terminal demo.

## Integration

- Include ZXing Core 3.5.3 for local QR rendering.
- Manifest: optional `uses-library android:name="com.pos.sdk" android:required="false"`; `com.pos.permission.PRINTER` and `android.permission.WAKE_LOCK`. Request the POS printer runtime permission on the device as appropriate.
- Route a selected inventory spool and the current server setting to `PrintingScreen(request, onBack, onSelectSpool)`.
- `PrintRequest(SpoolLabel(id, vendor, material, color, remainingGrams, location), serverUrl, qrMode)` is independent of inventory models. Adapt from the currently selected spool; do not cache a previous server URL.
- `PrintingViewModel` also injects `InventoryRepository` and `SettingsRepository`. Each print action verifies the selected server against persisted settings, reads the selected spool again, checks the server a second time, validates the returned ID and filament object, and builds the final label from the fresh response. The refreshed label replaces the preview after submission; QR mode and the selected paper tail are preserved. Loading is set synchronously to reject duplicate taps. Fetch/validation failures never enqueue or automatically retry; leaving the ViewModel cancels preparation.
- No physical printing occurs on entering the screen. Only the explicit print button enqueues a job. Printing never writes inventory or NFC data.

The screen previews all printed text and the actual locally encoded QR image. Product name (`filament.name`, e.g. PETG Pro) and material type (`filament.material`, e.g. PETG) are separate label lines; both the initial preview and fresh pre-print read preserve the full product name. A missing name falls back to material. The default QR links to `<configured base path>/spool/show/<id>`. The supplied backend redirects that path to its selected-spool UI. Optional `WEB+SPOOLMAN:S-<id>` is a separate QR payload; choosing both prints both QRs, not a concatenation.

## SDK methods and constants

`POIPrinterManager(Context)`, `open()`, `close()`, `getPrinterState()`, `cleanCache()`, `addPrintLine(PrintLine)`, `beginPrint(IPrinterListener)`, `setLineWrapPixels(int)`, and `lineWrapPixels(int)`.

`TextPrintLine(String, int, float, boolean)` and `BitmapPrintLine(Bitmap, int)`; alignment `LEFT=0`, `CENTER=1`. `IPrinterListener` is a Java interface with `onStart()`, `onFinish()`, `onError(int,String)`, so a Java dynamic proxy is valid.

Status: idle 0, printing 1, overheat 2, no paper 3, no printer 4. Error codes: initialization -1, print -2, overheat -3, no paper -4, other -999. The manager owns a reconnect broadcast receiver while open; close releases it. All reflected names belong to the externally installed vendor shared library, which R8 does not rewrite. The app uses its own listener through normal direct calls; no app-owned class is reflectively named and no extra app keep rule is needed. Release hardware testing still verifies runtime shared-library class loading and proxy callbacks.

## Queue, lifetime, and paper tail

A singleton Main-dispatcher worker serializes the vendor's Android-view layout calls and physical jobs. The FIFO holds at most eight waiting jobs. Duplicate or conflicting terminal callbacks complete a deferred once. Tail feed runs once after successful `onFinish`, with `setLineWrapPixels(1)` followed by `lineWrapPixels(dots)`. Saved choices are 80/120/160/200 dots (1/1.5/2/2.5 cm); the owner-confirmed default is 120 dots (1.5 cm).

The screen disables new printing while a job is pending. The screen holds an idempotent visibility lease only while composed and its lifecycle is STARTED. STOP, screen disposal, and ViewModel cleanup detach that lease, including when the ViewModel itself is activity-scoped; the manager closes only after queued work has reached a terminal callback. A bounded 120-second partial wake lock protects printing from ordinary CPU sleep. While the print screen is visible and work is pending, its view keeps the display on; composition disposal restores the previous flag on API 30 as well.

A 60-second callback deadline reports an unknown outcome and blocks further submissions and queued execution until the original callback arrives. It deliberately does not close the printer or automatically reprint after a timeout. This SDK surface has no verified cancellation acknowledgment: if a callback never arrives, the job stays quarantined for that process lifetime. The user must inspect the physical result before restarting the app and deciding to reprint. Background process death cannot guarantee physical-job recovery; this is not a durable print spooler.

## Verification

App preparation fixture tests cover fresh data, duplicate taps, server changes before/during fetch, failed reads, mismatched or incomplete records, and cancellation before dispatch. Hardware unit tests cover serialization, start/finish callbacks, duplicate terminal callbacks, missing-callback and dispatch-exception quarantine, delayed callbacks, detach during a job, no-paper/overheat, explicit retry after errors, rendering failure recovery, tail calibration, URI validation and base paths. Hardware acceptance still requires permission/service readiness, Chinese font layout, QR scanability, measured tail distance, and physical paper-out/overheat recovery on the P8 Neo. No automated check prints paper.
