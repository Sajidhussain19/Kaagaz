# Kaagaz: readable summaries and document types (10 October 2026)

## What changed

| Problem seen on device | Cause | Fix |
|---|---|---|
| "…only if every; Guaranteed Maturity Benefit … 15 June 2032,." | Summaries joined wrapped OCR *lines* with "; " | `ClauseAssembler` rebuilds whole sentences from wrapped lines (text rules, plus box alignment when boxes exist). Summaries now list one short line per benefit from that benefit's own sentence |
| The same clause shown in several cards | One passage per OCR line with ±1 line of context | One passage per sentence; the highlight covers exactly that sentence's lines; numbered and repeated section headings are not shown as clauses |
| Vehicle registration shown for a child policy | No idea of document type; all seven motor fields always shown | `DocumentProfile` detects motor, health, life/savings, payment statement or other. It shows only fields with a value, and lists relevant missing fields in one line. Found or edited values are never hidden |
| "Statement Date" saved as insured name (marked Supported) | Column-ordered tables: the line after a label was the next label | The extractor never accepts a known label as a value, and pairs a label with the value on the same row when boxes exist |
| "What is payable at maturity?" and "How much remains scheduled?" unsupported | Missing question phrasings | Added phrasings. All seven handover questions are now answered |
| Generic coverage explanations | Fixed text per category | Specific counts, for example "Found 1 waiting period and 2 exclusions (page 3)." |
| Benefit sentences mentioning "scheduled premiums" in their condition were labelled as schedules | Category order | The benefit/maturity label decides the category |
| Two different maturity amounts | The first amount was picked | Shown as Needs review, with both amounts |

UI (`MainActivity`):
- **Preview card:** now an "At a glance" card showing benefits by age and date, projected amount (not guaranteed), premium and frequency, and how many waiting periods and exclusions apply.
- **Coverage cards:** each card shows the source sentence and page, with one "Highlight in document" button. The category explanation appears once per group.
- **Questions:** suggested questions are chosen by document type.
- **Ask Kaagaz:** now answers coverage, benefit and payment questions as well as field questions.

Not changed:
- Database schema and migrations.
- OCR pipeline.
- Payment totalling and spatial matching.
- Reminders.
- Cross-document linking.
- Permissions.

## Verification status

- **Unit tests:** 67 of 67 pass (the original 38 plus 29 new).
  - These were run in a JVM harness with a stand-in for `android.graphics.Rect`, not through Gradle. Run Gradle on Windows to confirm.
  - The new tests use OCR-like fixtures generated from `output/pdf/kaagaz-fictional-child-*.pdf` by `tmp/pdfs/create_realistic_ocr_fixture.py`. Paragraphs wrap as on the page, and tables are read column by column.
- **`MainActivity.kt`:** **not compiled yet.** The Android SDK was not reachable from the build environment, so `assembleDebug` must be run on Windows.
- **Device testing:** not performed.
- **Originals:** the pre-change files are in `output/backup-before-readable-summary-2026-10-10/`.
- **APKs:** no APK was built or overwritten. `Kaagaz-cross-document-validation-v4.apk` still has SHA-256 `9CE7AD84…93B3`.

## Build and install (Windows, PowerShell)

```powershell
cd C:\Users\sajid\OneDrive\Documents\ChatGPT\p-b\kaagaz-fresh
.\gradlew.bat testDebugUnitTest assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk output\demo\Kaagaz-readable-summary-v1.apk
Get-FileHash output\demo\Kaagaz-readable-summary-v1.apk -Algorithm SHA256
Get-FileHash output\demo\Kaagaz-cross-document-validation-v4.apk -Algorithm SHA256   # must still be 9CE7AD84…
adb install -r output\demo\Kaagaz-readable-summary-v1.apk
```

Documents saved earlier keep the fields extracted then. **Run OCR again** re-extracts them with the fixed extractor, but, as before this change, it also replaces any manual field edits on that document.

## Device checks to run

1. Open the fictional child policy. The At a glance card should show education INR 1,00,000 at 18 on 15 June 2029, maturity INR 1,50,000 at 21 on 15 June 2032, projected INR 40,000 (not guaranteed), and the premium. There should be no vehicle field.
2. Ask the seven handover questions in Ask Kaagaz, with the payment statement linked.
3. Tap "Highlight in document" on the education benefit. Both lines of the sentence should be highlighted on page 2.
4. Check that the motor demo policy still shows all seven fields, including vehicle registration.
