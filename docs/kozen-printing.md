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

## Software-defined fixed labels

`PrintRequest.paper == null` retains continuous receipt printing and its saved 1.5 cm default tail. `PaperTemplate` selects fixed geometry: default usable width 48 mm, height 60 mm, gap 2 mm, margin 2 mm, 20-dot font, 24 mm QR, and ID/full name/vendor/material/color/QR fields. It supports a named profile, optional other fields, and nonnegative right/down offsets. Nominal two-inch paper width is distinct from the 384-dot SDK raster. Application sizing uses a calibration of 8 dots/mm, consistent with the prior owner-confirmed paper-tail test; physical head DPI remains unverified. This calibration allows at most 48 mm of usable raster width.

Validation accepts width 20–48 mm, height 20–200 mm, gap 0–20 mm, margins 0–10 mm, right offset 0–48 mm, down offset 0–200 mm, font 12–48 dots, and QR 10–44 mm. Insets and selected content must also fit. Invalid/nonfinite geometry, text overflow, QR overflow, and dense QR symbols smaller than two dots/module are rejected before `beginPrint`. Complete product names wrap by Unicode code point; they are never truncated. Control characters become spaces without removing ordinary letters.

`LabelLayout.plan` is a pure layout planner with injected font measurement. `renderLabelBitmap` supplies Android Paint measurement and draws that plan; the fixed-mode preview and printer use this same renderer. The bitmap is exactly 384 dots wide and `round(heightMm * 8)` dots high, with narrower usable widths centered. Text uses binary pixels and QR rendering uses integer module scaling with a four-module quiet zone. The bitmap has `DENSITY_NONE` to prevent BitmapDrawable density scaling.

The fixed path renders and validates before touching the SDK cache, then uses `cleanCache`, `setLineSpace(0)`, and exactly one bitmap line. The exclusively owned manager starts with zero root padding; neither receipt nor fixed code calls `setBottomSpace` or `addBlankView`. Reverse inspection found that width <=384 and height <=100000 bitmaps are retained, PrinterLayout measures width EXACT384, and full-height raster conversion preserves white rows. At 384 pixels, packed rows are exactly 48 bytes, so native whole-row padding does not add rows. `cleanCache` alone does not repair arbitrary accumulated root bottom padding, which is why this integration never shares a manager with other SDK layout users.

After the normal terminal callback, fixed mode advances only `round(gapMm * 8)` additional dots; zero gap sends no feed. It never appends the continuous receipt tail. The captured immutable profile determines each queued job. Total software advance is the full label canvas plus the selected gap; blank margins remain part of the canvas. These controls do not detect die-cut gaps or black marks, do not backfeed, and cannot automatically restore registration after a manually advanced or misaligned roll. Physical dot-to-mm calibration and alignment still require a measured device check.

Layout tests cover exact canvas dimensions, centering, forward/right offsets, full Unicode wrapping, overflow rejection, field selection, quiet zones and integer QR scaling. Controller tests cover fixed gap-only feed, zero gap, invalid profiles, and immutable field capture; existing callback/quarantine tests remain applicable.

Fixed-gap feed uses the existing manager-owned private `printer` field and its public `PosPrinter.setPrintCtrlFeed(int)` method. Reverse evidence shows the single-argument overload passes dot count with black-mark flags zero and returns the service result code (0 means accepted). Unlike `lineWrapPixels`, it does not silently discard that result. Missing/null reflection state or a nonzero result raises a feed error after content completion; no fallback command or automatic retry is sent. It opens no second lease and changes no global calibration. Acceptance is not independent physical movement verification. Continuous receipt feeding retains its previously verified manager wrapper behavior.
