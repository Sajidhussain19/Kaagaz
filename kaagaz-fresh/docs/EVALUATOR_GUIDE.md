# Kaagaz: evaluator guide

**One line:** Kaagaz is a private, on-device paperwork agent. It reads your documents, tells you what to do today, fills your forms (even paper ones) only with facts it can prove, and never lets AI guess.

## Why Kaagaz

| Everyday problem | What people do today | What Kaagaz does |
|---|---|---|
| Forms ask for the same 15 facts; one wrong value means rejection | Dig through certificates, copy by hand, hope nothing mismatches | Fills the **original paper form** with verified values, and leaves blank anything your documents disagree on |
| Your documents contradict each other (graduation year, date of birth) | Find out when an office rejects you | Finds it first, shows both sources, and **drafts the correction letter** |
| Bills, EMIs and premiums are spread across papers | Miss due dates; pay a bill twice | **Today** lists what to pay and when; flags duplicate bills; reminders |
| AI chat apps make things up | Can't tell what's true | Every answer cites document, page and line. Invented numbers or names are **rejected**, shown on screen |
| Privacy: personal documents in the cloud | Upload and trust | **No internet permission.** OCR and Gemma run on the phone |

## Setup (2 minutes, before evaluators arrive)

1. Install `output/demo/Kaagaz-mission-os-v8.apk`.
2. Open **Privacy & model** and import `gemma3-1b-it-int4.litertlm`, then wait for **GEMMA · GPU** on the home screen.
3. On the home screen, tap **Install demo pack**: 8 clearly labelled fictional documents (résumé, certificate, transcript, bills, loan, blank scholarship form).
4. Close Vivo Remote Control. Optionally turn on airplane mode to prove everything is offline.

## The walkthrough (about 5 minutes)

| # | Show | Tap | Say |
|---|---|---|---|
| 1 | **Privacy** | The home chips **ON-DEVICE · NO NETWORK PERMISSION · GEMMA · GPU** | "Nothing leaves this phone. The app has no internet permission at all, and Gemma runs on the GPU." |
| 2 | **Today** | Read the card | "Kaagaz tells me what to do: confirm my graduation year (my résumé says 2024, my certificate says 2023), don't pay a bill twice, and pay my ₹8,958 loan EMI by Sunday." |
| 3 | **Fix-it Letter** | **✉ Draft fix letter** under "Confirm your graduation year" | "It writes the email to the registrar, quoting both documents with page numbers. I send it from any app; Kaagaz never sends anything itself." |
| 4 | **Loans & EMIs** | Scroll to the card, then **+ Remind me** | "It read my ₹1,00,000 loan statement: this month's EMI, due date, outstanding principal. One tap sets a reminder for 2 days before." |
| 5 | **Notes & reminders** | **Notes & reminders**, type a note, then **Tomorrow 9 AM** | "Private notes with reminders that work offline. It also suggests reminders from deadlines it found." |
| 6 | **Trust Ledger** | Tap the ledger card | "Every fact carries its source. The red edges in the evidence graph are the contradictions. Kaagaz never picks one silently; I resolve them." |
| 7 | **Ask (Gemma)** | **Ask Kaagaz**: "What happens if the policyholder dies?" | "A grounded Gemma answer, with a citation, in under a second." |
| 8 | **AI caught out** | Ask: "How many units were consumed?" | "Gemma said 12; the bill says 312. Kaagaz checks every number against the evidence, so it rejected the answer and showed the exact line. This is the difference: proof-carrying AI." |
| 9 | **Conflict refusal** | Ask: "What is my graduation year?" | "It refuses to guess and shows both sources." |
| 10 | **Paperwork Crash Test** | **Audit documents → Run crash test** | "Conflicts, the duplicate bill, deadlines, and over-reaching form fields (net-banking password, full Aadhaar) are flagged for review." |
| 11 | **★ Print-ready paper form** | **Fill a form → scholarship-application-form.pdf → 🖨 Print-ready filled form**, then open the PDF | "This is the *original paper form*, filled in at the right places from my verified documents. Conflicts, bank details and password are left blank with a reason; there's a sources page at the back. Print, sign, submit. No browser autofill can do this." |
| 12 | **Mission Mode** | Home: **Prepare my university application…** → Launch, untick all but "Fill", then **Approve** | "Gemma plans from a whitelist, Kotlin executes, and nothing consequential happens without approval. Rejected steps say 'nothing was changed'." |
| 13 | **Proof Pack** | **Proof Pack** tile | "A portable report: every value, its citation, and a SHA-256 fingerprint of each source file." |
| 14 | **Capture anywhere** | Share a PDF from Files to **Kaagaz: read privately**, or **Camera scan** | "Works from any app's share sheet or the camera. One on-device pipeline." |

## Features at a glance

- **On-device AI:** Gemma 3 1B through LiteRT-LM on the GPU; ML Kit OCR; no network permission.
- **Proof-carrying answers:** every answer is checked against its citations, and invented numbers or names are rejected.
- **Trust Ledger and evidence graph:** VERIFIED / CONFLICTING / MISSING / NEEDS CONFIRMATION.
- **Today:** plain-language daily actions.
- **Fix-it Letters:** correction requests and bill disputes.
- **Loans & EMIs:** this month's EMI, due date, outstanding principal and reminder.
- **Bills:** due dates and duplicates. **Insurance:** cover, exclusions, waiting periods, guaranteed vs projected.
- **Notes & reminders:** offline notifications, plus suggestions from documents.
- **Print-ready paper forms;** tap-to-copy for online forms.
- **Paperwork Crash Test,** **Mission Mode** with approval and verification, **Proof Pack** with SHA-256 fingerprints.
- **Intake:** share sheet, camera, file picker, one-tap demo pack. Type-aware summaries for each document.
- **Privacy:** encrypted profile (Keystore AES-GCM), backups off, no content logging.
- **Engineering:** 133 unit tests, feature-package architecture, and a device verification log in [TESTING.md](TESTING.md).

## If asked

- **"Is the AI really on the phone?"** Yes. Turn on airplane mode and ask again. The Privacy screen shows the model file and that it runs on the GPU.
- **"What if Gemma is wrong?"** That's step 8: wrong numbers and names are removed and the exact source is shown instead.
- **"Can offices accept the filled form?"** It's their own form, filled in, which you print and sign yourself. Kaagaz never signs or submits.
- **"Limits?"** Gemma 1B can phrase things loosely (only names and numbers are checked). Form placement depends on scan quality. There are no semantic embeddings. See [JUDGING.md](JUDGING.md).
