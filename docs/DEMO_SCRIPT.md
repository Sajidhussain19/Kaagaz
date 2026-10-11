# 3-minute demo script

**Setup (before judges arrive):**
1. Install the APK.
2. Import Gemma under **Privacy & model** and wait for **GEMMA · GPU** on the home screen.
3. Tap **Install demo pack**, or use a library that already has it.
4. Optional: turn on **airplane mode**. Everything below works offline.

| Time | Do | Say |
|---|---|---|
| 0:00 | Home screen | "Kaagaz is an on-device paperwork agent. No internet permission at all; Gemma runs on this phone's GPU. Today tells me exactly what to do: confirm my graduation year (résumé says 2024, certificate says 2023), don't pay a bill twice, pay ₹2,340 by Tuesday." Tap **Draft fix letter**: "And it writes the email to the registrar for me, quoting both documents with page numbers. I send it from any app; Kaagaz never sends anything itself." |
| 0:20 | Open the **Trust Ledger** | "Every fact carries its document, page and the exact OCR line. The red edges are the planted conflicts: graduation year 2023 vs 2024, and date of birth March vs May. Kaagaz never silently picks one." |
| 0:45 | **Ask:** "What is my graduation year?" | "It refuses to guess and shows both sources." |
| 0:55 | **Ask:** "What happens if the policyholder dies?" | "This is Gemma: a grounded answer in under a second, with a citation." |
| 1:05 | **Ask:** "How many units were consumed?" | "Watch this one. Gemma said 12; the bill says 312. Every number must appear in the cited evidence, so Kaagaz rejected it and showed the exact line. That's proof-carrying AI." |
| 1:30 | **Audit documents → Run crash test** | "The Paperwork Crash Test, computed from the documents and not scripted: conflicts, a duplicate bill, a deadline, and two over-reaching fields on the form. A net-banking password and a full Aadhaar number are flagged for review and never filled." |
| 2:00 | **Start a Mission:** "Prepare my university application and show me what is missing" | "Gemma plans from a whitelist; Kotlin executes. Everything stops at approval. I'll approve only the form fill." Approve one step. "It filled 8 verified fields, verified them by read-back, and submitted nothing. The rejected steps say 'nothing was changed'." |
| 2:30 | **Fill a form → scholarship-application-form.pdf** | "This is a real blank form; Kaagaz read its 12 fields with on-device OCR. It fills 7 from verified facts, each with its source, and tap-to-copy pastes them into any website. Date of birth and year of passing are held back as conflicts, the bank and net-banking fields are flagged, and it never signs." Tap **🖨 Print-ready filled form**, then open it: "And this is the part no browser can do. It's the *original paper form*, now filled in at the right places from my verified documents. Conflicts and sensitive fields are left blank with a reason, there's a sources page at the back, and I print it, sign it and submit it at the office." |
| 2:50 | Share a PDF from Files to **Kaagaz: read privately** (optional) | "It works from any app's share sheet, and the camera too. One on-device pipeline." |

**If something goes wrong:**
- **Gemma not loaded:** answers are labelled "Policy engine" or "Best matching passage · no LLM". Say so; every other feature still works.
- **Demo documents missing:** use **Install demo pack** on the home screen. It takes about 10 seconds.
