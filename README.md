# Kaagaz — Private On-Device Paperwork Agent

Hackathon starter repository for the iQOO Hackathon 2026 Grand Finale.

## Product flow

`document input -> OCR -> classification -> extraction -> verification -> evidence -> timeline -> Q&A`

The starter deliberately keeps Android UI, AI logic, sample data, and demo material separate so each part can evolve quickly during the event.

## Layout

- `android/` — native Android client (Kotlin recommended)
- `ai/` — testable Python prototypes and evaluation code
- `docs/` — architecture, setup log, and readiness notes
- `assets/` — non-sensitive design assets
- `demo/` — synthetic demo documents and scripts only
- `scripts/` — repeatable local checks and helper scripts

## First checks

```powershell
py -3.12 -m venv ai\.venv
ai\.venv\Scripts\python -m pip install -e ai
ai\.venv\Scripts\python -m unittest discover -s ai\tests
```

Do not add real personal paperwork, credentials, keystores, or downloaded model weights to this repository.
