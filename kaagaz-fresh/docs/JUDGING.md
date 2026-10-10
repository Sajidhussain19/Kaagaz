# Judging map

Each criterion is paired with evidence a judge can check, and with what Kaagaz does **not** claim.

| Criterion | What to look at | Evidence | Honest limits |
|---|---|---|---|
| **Genuine AI capability** | Ask Kaagaz; Mission planning | Gemma 3 1B int4 runs through LiteRT-LM on the phone's **GPU**, with answers in 0.6–0.9 s on a Snapdragon SM8850. The app shows **GEMMA · GPU** only after a real generation succeeds. | It's a 1B model: wording is occasionally imperfect, and it tends to over-plan. That's why every answer is validated and every plan goes through the whitelist and approval. |
| **Trustworthy AI (originality)** | The "units consumed" question; Trust Ledger; Proof Pack | Numbers not present in the cited evidence are rejected, conflicts are refused, and every value carries document, page and quote. The Proof Pack adds SHA-256 source fingerprints. | Validation checks numbers and citations, not every nuance of wording. |
| **Practical productivity** | Crash Test, Smart form, reminders, Today's Brief | Duplicate bill, deadlines, outstanding premium, and form fill from verified facts with read-back verification. Reminders run through WorkManager. | The form is an in-app form; there is no Android Autofill service for other apps or websites. |
| **Technical depth** | [ARCHITECTURE.md](ARCHITECTURE.md) | A mission state machine with safety rails, BM25 combined with fact-match ranking via reciprocal-rank fusion, a derived knowledge snapshot, an encrypted vault, a native-crash guard for the model runtime, and OCR quirks found and fixed on the real device. | No embedding model, so there is no semantic retrieval. |
| **Creative mobile use** | Share to Kaagaz, camera scan, airplane mode | Share-sheet target for PDFs and images, camera capture, GPU inference, local notifications, and a Keystore-backed vault. | Camera capture uses the system camera app, with no in-app edge detection. |
| **Privacy** | Privacy & model screen | No INTERNET permission (checked at runtime and shown), app-private storage, backups disabled, no content logging, and sharing only on user action. | The model file must be obtained by the user under Google's Gemma terms. |
| **Polish and end-to-end experience** | The whole demo | One coherent dark mission-control UI. Every control is wired; loading, empty and error states exist. A one-tap fictional demo pack. | Some legacy document screens are still dense. |
| **Engineering quality** | `app/src/test` | **119 JVM unit tests**, feature packages, explicit seams, and git history from a baseline snapshot. | Instrumented UI tests were replaced by scripted adb checks on the device; see [TESTING.md](TESTING.md). |
