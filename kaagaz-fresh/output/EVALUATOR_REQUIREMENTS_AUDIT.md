# Kaagaz evaluator-requirements audit — 10 October 2026

## Requirements matrix

| Requirement | Current implementation | Evidence of verification | Gap or failure | Minimal fix | Regression tests required |
|---|---|---|---|---|---|
| Explicit disease coverage | Deterministic retrieval requires benefit wording near the disease | Validation matrix and device coverage fixture | English phrase rules only | Add terms only for reproduced misses | Positive coverage plus mention-only negative case |
| Exclusions | Explicit exclusion/non-payable wording is classified separately | Unit matrix; device page-2 highlight | May miss unfamiliar legal wording | Add narrowly scoped phrases | Exclusion must never become benefit |
| Conditional coverage and waiting periods | Conditional and waiting-period categories retain cited OCR lines | Unit matrix for diabetes, 24 months and 90 days | Cross-page conditions are not joined | Fail closed when local evidence is insufficient | Conditional, waiting, and conflicting clauses |
| Limits | Caps, sub-limits, per-day and per-claim wording supported | Unit matrix; fictional room-rent and ambulance clauses | Complex tables may split labels/values | Geometry-aware pairing only after a reproduced case | Wrong-row and missing-box negatives |
| Cannot determine | Missing or mention-only disease wording returns an honest non-answer | Dengue/tuberculosis tests; device dengue returned Not found when absent | Disease vocabulary is finite | Keep unsupported terms as missing rather than guessing | Unknown disease and definition-only mention |
| Child benefits at ages/dates | New deterministic maturity intent recognizes explicitly labelled child/education benefits and requested ages | New unit tests for age 18 and missing age 21 | No child-policy PDF has been run through device OCR | Add a fictional child-policy fixture, then perform device OCR | Supported age, wrong age, missing amount, conflicting ages |
| Maturity amount/date | Summary now includes amount/date only from a line carrying an explicit maturity/benefit label | Unit tests and corrected device answer: INR 1,00,000 on 31 December 2036 | Split OCR may cause otherwise supported values to be omitted | Add geometry association only with strict label/row checks | Unrelated nearby date must never attach |
| Premiums paid | Totals only explicit payment records; supports same-row OCR geometry | Unit matrix; device total ₹2,500 | Tables without boxes fail closed | None without a reproduced fixture | Receipt-only, schedule-only, wrong-row amount |
| Future premiums vs outstanding dues | Separate categories and wording | Unit matrix; device INR 500 outstanding kept distinct | Multiple installments are listed as passages, not totalled | Add aggregation only if evaluator requires it | Schedule must not become debt |
| Guaranteed vs projected benefit | Separate categories; projected answer says not guaranteed | Unit matrix | Guarantee classification depends on explicit wording | Preserve fail-closed behavior | Projection near guarantee; projection-only document |
| Important dates | Uses explicit OCR labels plus supported/confirmed structured dates | Unit matrix | Does not infer unlabeled dates | No change | Unconfirmed edit and unrelated dates excluded |
| Clause, page and highlight | Every displayed passage retains OCR evidence and page; overlay uses stored boxes | Unit evidence/page assertions; physical highlight on policy page 2 and payment page 1 | Highlight unavailable when boxes are absent | Continue showing text fallback | Page selection, null/invalid boxes, multiple regions |
| One-to-two sentence answers | Summary generation is capped to concise deterministic responses; details dialog shows source wording | 17-case matrix and UI inspection | Broad automatic summary can select an OCR heading | Improve only with a reproduced incorrect summary | Sentence-count and evidence-source tests |
| Local storage | Private copied files plus SQLite for OCR, fields, evidence and reminders | Device: 23 documents; document files 400 KiB allocated; databases 308 KiB allocated; `kaagaz.db` 270,336 bytes | No encryption-at-rest layer; Android sandbox is the boundary | Consider encrypted storage only after threat-model decision | Migration, corruption and storage-full tests |
| Performance | Sequential local OCR and indexed document IDs | iQOO I2501: import median 2,539 ms; one-page OCR median 2,417 ms; library median 2,664 ms | Measurements include 200 ms UI polling and small synthetic files | Profile only if judge device shows lag | Large PDF and 100-document library benchmark |
| Rental/lease agreements | Generic import, preview, OCR and raw OCR viewing work | Architecture inspection only | No lease-specific extraction, Q&A, evidence taxonomy or device fixture | Separate future milestone | Fictional lease end-to-end fixture |
| Loans/EMIs | Generic import/OCR only | Architecture inspection only | No EMI-specific structured extraction or answers | Separate future milestone | Fictional loan schedule fixture |
| Bills/receipts/expenses | Generic import/OCR only; explicit insurance payment records supported | Payment fixture is insurance-specific | No general expense model, totals or receipt workflow | Separate future milestone | Fictional receipts with duplicate/tax/total cases |
| Offline privacy | Bundled OCR and deterministic retrieval; no network permission | Airplane-mode device run; merged APK permission audit | Notification scheduling remains an Android local service dependency, not network | None | Permission audit on every candidate |
| Regression safety | Existing OCR, persistence, editing, Q&A, evidence and reminder code unchanged except coverage engine/tests | Full 28-test suite and debug build passed; `install -r` retained 23 documents | Not every legacy screen was manually repeated this pass | Retain protected APK fallback | Full suite plus focused device smoke test |

## Fixes made

1. Financial answers now surface explicitly supported future-premium, maturity and projected-benefit amounts/dates.
2. Child/education benefits payable at a requested age are recognized only when that same age appears in labelled source wording.
3. A device-discovered false association was fixed: an unrelated nearby payment date can no longer be attached to a maturity amount.

## Test conditions and measurements

- Physical device: iQOO I2501.
- Offline: airplane setting enabled, Wi-Fi and mobile data disabled; normal connectivity restored afterward.
- Timing method: stopwatch from UI action to a matching `uiautomator` state, polled every 200–250 ms; includes automation overhead.
- Import to preview, two-page fictional policy: 2,643 / 2,511 / 2,539 ms; median 2,539 ms.
- One-page fictional payment-statement OCR, 42 lines: 2,443 / 2,407 / 2,417 ms; median 2,417 ms.
- Library open with 19 documents: 2,664 / 2,639 / 2,724 ms; median 2,664 ms.
- Final storage snapshot with 23 documents: `files/documents` 400 KiB allocated, all app-private files; databases 308 KiB allocated; main database 270,336 bytes.

## Remaining scope

Rental/lease, loan/EMI, and general bill/receipt understanding are not evaluator-ready. Only their generic import, preview, local OCR, persistence, and raw evidence infrastructure exists. They must not be presented as supported document-intelligence workflows.
