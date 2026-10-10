# Attribution and originality

## Third-party components

| Component | Use | Licence / terms |
|---|---|---|
| Google ML Kit Text Recognition (Latin, bundled model) | On-device OCR | Google APIs Terms of Service |
| LiteRT-LM Android 0.16.1 (`com.google.ai.edge.litertlm`) | On-device LLM runtime | Apache License 2.0 |
| Gemma 3 1B IT int4 (`gemma3-1b-it-int4.litertlm`, litert-community) | Language model: **user-supplied, not bundled** | Gemma Terms of Use |
| AndroidX Activity, Core, WorkManager | App framework, background reminders | Apache License 2.0 |
| Gson (transitive, via LiteRT-LM) | Plan JSON parsing, profile serialisation | Apache License 2.0 |
| ReportLab (development only) | Generates the fictional fixture PDFs | BSD |

The Android SDK, Gradle and Kotlin are used as build tools.

## What existed before this work

Git history marks the boundary. Commit `3c955af` ("Snapshot kaagaz-fresh before Mission OS work") captures the app as it was. That earlier Kaagaz app provided:
- Document import and the ML Kit OCR screen (now `ui/DocumentActivity`)
- `InsuranceExtractor`, `CoverageIntelligenceEngine`, `CrossDocumentCoverageEngine`, `DocumentQaEngine`, `DocumentProfile`, `ClauseAssembler`
- Policy-expiry reminders, and their 76 unit tests

## Built in the Mission OS work (commits after `3c955af`)

- Fact layer, Trust Ledger and evidence graph
- Paperwork Crash Test
- Smart form mapping and sensitive-field detection
- Hybrid retrieval and validated grounded answering
- Mission planner and state machine with approval and verification
- Gemma runtime integration, crash guard and auto-load
- Encrypted profile vault
- OCR engine and ingestion pipeline; share-sheet and camera intake; demo pack
- Proof Pack export
- Today's Brief
- Mission OS UI and design system, and the dark re-skin of the document screens
- Package architecture
- 43 new unit tests
- All documentation in `docs/`

AI coding assistance (Claude) was used during development; the commits carry a `Co-Authored-By` trailer.

## Test data

Every document in `output/pdf`, `app/src/main/assets/demo` and the test fixtures is **fictional** and labelled as such on the page. Names such as "Aarav Mehta", "Kaveri Institute of Technology" and "Demo Power Distribution" are invented.
