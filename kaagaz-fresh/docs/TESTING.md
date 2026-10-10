# Testing

## Automated (JVM unit tests): 119 passing

```powershell
./gradlew.bat testDebugUnitTest
```

| Suite | Tests | Covers |
|---|---:|---|
| `integration.MissionOsEngineTest` | 40 | Classification; extraction with page evidence; real ML Kit OCR quirks (spaced email, wrapped certificate sentence, title lines, heading/label confusion); column tables without geometry; ledger conflicts, name variants, low confidence and user resolution; Crash Test (conflicts, duplicates, exact deadline days, sensitive fields, unsupported values, missing documents, outstanding premium); form mapping; retrieval relevance and abstention; validation of Gemma answers (accepted, invented number, missing citation, NOT_FOUND); fallback with no model; a 300-page document; planner whitelist, malformed JSON, truncated JSON, line format; approval gating, skipped steps, failure, cancellation |
| `integration.DailyBriefTest` | 3 | Urgency ranking from real fixtures; overdue first; empty state |
| `insurance.*` (9 suites) | 63 | Pre-existing policy engine: coverage, exclusions, waiting periods, payments, guaranteed vs projected, cross-document checks, answer safety |
| `core.ClauseAssemblerTest` | 6 | Sentences rebuilt from wrapped OCR lines |
| `reminders.ReminderPolicyTest` | 7 | Reminder eligibility and dates |

Fixtures are **fictional**. `MissionOsFixtures` reproduces, line for line, what ML Kit returned for the demo PDFs on the test phone.

## On-device verification log

Device: vivo I2501, Android 16 (API 36), Snapdragon SM8850, 16 GB RAM. Checked by scripted adb with screenshots, 10–11 Oct 2026.

| Check | Result |
|---|---|
| Install over the previous version (data preserved, database v3 → v4) | Pass |
| Import and OCR of 5 demo PDFs | Pass: 10–18 lines each |
| Trust Ledger on real OCR | Pass: exactly the 2 planted conflicts; 9 verified |
| Gemma import (584 MB) and verification | Pass: **GPU**, first reply "READY" |
| Gemma auto-load after restart | Pass: about 3 s |
| Grounded Gemma answer | Pass: about 0.8 s, cited |
| Gemma's wrong number rejected | Pass: "12" rejected, "Units consumed: 312" shown |
| Mission planned by Gemma | Pass: 0.9 s, whitelist-validated |
| Selective approval | Pass: only the form fill ran and was verified; others "nothing was changed" |
| Bill reminder scheduled | Pass: WorkManager job registered, row read back |
| Encrypted profile | Pass: file is ciphertext; the plaintext name is absent |
| Proof Pack export | Pass: 3 pages; SHA-256 matches the original file on the PC |
| Share to Kaagaz from the Files app | Pass: imported and OCR'd (11 lines) |
| Camera scan | Pass: camera opens through FileProvider; cancel cleans up |
| Demo pack install | Pass: real OCR pipeline |
| Corrupt model file | Pass after fix: rejected at import; a crashed load is never retried |

## Bugs found by device testing (all fixed)

- OCR read "DEGREE CERTIFICATE" as a degree value, mixed the "Skills" heading with the next line, and broke the email at the `@`. These caused false conflicts; the fixture now mirrors the real OCR.
- A corrupt model aborted the process in native code. Fixed with a size check and a loading marker.
- Gemma 1B could not produce reliable JSON plans. Added a whitelisted line format and strict shape checks.
- MediaStore returned no MIME type for shared files. Intake now falls back to the declared type, then the file name.
- OneDrive placeholders corrupted Gradle outputs. The build directory moves outside OneDrive.
