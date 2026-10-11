# Kaagaz focused validation — 10 October 2026

## Scope and method

- Deterministic question matrix: 17 cases against fictional OCR fixtures in `CoverageValidationMatrixTest`; every result asserted a supported answer class, concise summary (at most two sentences), non-empty evidence for displayed clauses, and valid source pages.
- Device: physical iQOO I2501 (`10BFAU14XB000XR`), candidate installed with `adb install -r` so app data was retained.
- Offline method: Android airplane setting was 1, Wi-Fi was disabled, mobile data was disabled/disconnected, and ADB launch reported no connected network. Original connectivity state was restored afterward.
- Timing method: PowerShell `Stopwatch` from UI tap until a matching `uiautomator` state appeared, polled every 200–250 ms. Figures include polling/dump overhead and are not benchmarks.

## Question matrix

| # | Question | Expected | Actual | Source page | Correct | Concise |
|---:|---|---|---|---:|:---:|:---:|
| 1 | What does this policy cover? | Explicit document-supported benefit | Emergency ambulance benefit is payable up to INR 2,000 per claim. Conditions, exclusions, waiting periods, and limits in the policy may still apply. | 1 | Yes | Yes |
| 2 | What benefits do I get? | Document-supported benefits only | Emergency ambulance benefit is payable up to INR 2,000 per claim. Conditions, exclusions, waiting periods, and limits in the policy may still apply. | 1 | Yes | Yes |
| 3 | What exclusions are listed? | Cosmetic-treatment exclusion | These passages are worded as exclusions or non-payable situations, not benefits. | 1, 2 | Yes | Yes |
| 4 | Are there waiting periods? | 24-month and 90-day waiting wording | These passages describe time that may need to pass before specified coverage applies. | 1, 2 | Yes | Yes |
| 5 | What conditional benefit is described? | Conditional coverage, not unconditional | These passages describe coverage that depends on conditions. Review the original wording before relying on the benefit. | 1 | Yes | Yes |
| 6 | Is dengue covered? | Mention only; unable to determine coverage | A disease or condition is mentioned, but these passages do not establish that treatment is covered. Check the complete policy or insurer. | 1 | Yes | Yes |
| 7 | Does this policy cover diabetes? | Conditional after 24 months | The condition is conditionally covered, subject to the waiting period or requirements shown below. | 1 | Yes | Yes |
| 8 | Does this policy cover tuberculosis? | Missing evidence | I could not find relevant disease or condition wording in the saved OCR text. Check the complete policy or insurer before relying on this result. | — | Yes | Yes |
| 9 | What limits or sub-limits apply? | Room-rent and benefit caps | These passages describe limits, sub-limits, caps, or conditions on payment. | 1, 2 | Yes | Yes |
| 10 | What are the claim requirements? | Claim form and discharge summary | These passages describe documents, notice, or other claim requirements. | 2 | Yes | Yes |
| 11 | How much have I paid? | Verified total ₹2,500 | The available payment records show a verified total of ₹2500. | 1 | Yes | Yes |
| 12 | What future premium is scheduled? | INR 1,500 schedule, not outstanding | The document lists a future scheduled premium below; this does not prove that the amount is currently outstanding. | 1 | Yes | Yes |
| 13 | How much premium remains? | Verified outstanding INR 500 | The document explicitly lists an outstanding premium of INR 500 below. | 1 | Yes | Yes |
| 14 | What guaranteed maturity benefit will I receive? | Guaranteed INR 1,00,000 with conditions | The document states a guaranteed maturity benefit below. Payment depends on the eligibility conditions in the source clause. | 1 | Yes | Yes |
| 15 | What projected benefit is shown? | Projected INR 1,25,000; not guaranteed | The document shows a projected benefit below. It is explicitly not guaranteed. | 1 | Yes | Yes |
| 16 | When will I receive the benefit? | 31 Dec 2036, amount, and eligibility clause | The document states a guaranteed maturity benefit below. Payment depends on the eligibility conditions in the source clause. | 1 | Yes | Yes |
| 17 | What important dates should I know? | Supported start, expiry, due, and maturity dates | Policy Start Date: 01 January 2027; Policy Expiry Date: 31 December 2036; Premium Due Date: 01 April 2027; Maturity Date: 31 December 2036. No unsupported due or maturity dates are added. | 1 | Yes | Yes |

## Evidence and false-positive checks

- All 17 matrix cases passed evidence/page assertions; the missing-evidence case returned no fabricated source.
- Physical payment answer reopened after force-stop/reinstall as ₹2,500. `Highlight source` opened PDF page 1 and reported four regions: both explicit payment labels and both same-row amounts.
- Dengue mention did not become coverage; diabetes remained conditional; a scheduled premium did not become an outstanding due; projected maturity remained explicitly non-guaranteed.
- Logcat was cleared before OCR and searched afterward for the fictional policy number, name, paid amounts, and maturity wording; no matching document content was found.

## Regression and performance results

- Unit tests: 25 passed, 0 failed (`testDebugUnitTest`).
- Debug build: passed (`assembleDebug`).
- Import and PDF preview, offline: 2,602 ms for the 3.19 kB one-page payment PDF.
- OCR, offline: 2,680 ms for one A4 page; 42 OCR lines produced.
- Library open: 2,666 ms with 16 saved synthetic documents.
- Restart/reopen: passed through force-stop and three `install -r` upgrades; saved OCR, current question, answer, source page, and highlighting were restored.
- Deletion: explicit confirmation dialog shown; test payment record was deleted and disappeared from the saved library. The Downloads fixture was intentionally retained.
- Reminders: three future WorkManager/SystemJobScheduler jobs remained registered after restart/reinstall; notification delivery was not waited for or claimed.
- Existing Q&A, reminders, extraction, editing, and confirmation logic are covered by the full unit suite. This pass did not repeat every one of those flows manually on-device.

## APK and permission audit

- Candidate: `output/demo/Kaagaz-validation-candidate.apk`
- Size: 51,380,159 bytes (49.00 MiB).
- SHA-256: `0F9C605EB34429E5EA8B99919CB929683CAF1E82091FBA2ADF0F62CE049DD103`
- Protected known-good APK remained unchanged; SHA-256: `9F36B3329AC650224DEF2C4AF39B296917507CF4D013B858D4524975BB046F14`.
- Merged permissions: `POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, and WorkManager's non-exported dynamic receiver permission. `INTERNET` and `ACCESS_NETWORK_STATE` are absent.

## Verified defects fixed

1. “Is dengue covered?” was not recognized as a disease query.
2. Nearby negative wording could contaminate a broad benefit summary.
3. Future scheduled premiums could be presented as outstanding dues.
4. Projected maturity could be grouped with guaranteed maturity.
5. Important-date summaries could expose long repeated context instead of a concise list.
6. Table OCR separated paid labels from amounts; geometry-aware same-row matching now totals only explicit payment rows and highlights the exact labels and amounts.
7. The fictional disclaimer could be classified as a payment record; it is no longer a payment trigger.

## Limitations

- Timings are single-run interaction measurements on one device and include UI-automation overhead.
- The spatial payment matcher intentionally declines to total amounts when OCR boxes are missing or not aligned closely enough.
- The synthetic payment statement is a life-policy-style validation fixture; the seven-field motor-insurance extractor is not expected to populate all of its metadata.
- Actual future notification delivery, missing-private-file recovery, and every Q&A prompt were not manually repeated in this pass.
