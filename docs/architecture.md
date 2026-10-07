# Kaagaz starter architecture

## Recommended primary stack

Native Android with Kotlin. It minimizes framework layers, uses Android's camera and document APIs directly, and has the shortest path to on-device ML libraries. Python remains a prototyping and evaluation workspace, not a runtime dependency of the Android app.

## Modules

1. `ingest`: camera, share intent, image/PDF normalization
2. `ocr`: OCR engine adapter returning text blocks and bounding boxes
3. `classify`: document type plus confidence
4. `extract`: typed fields with candidate source block IDs
5. `verify`: compare normalized extracted values with OCR evidence
6. `evidence`: map every accepted value to page and bounding box
7. `timeline`: derive deadlines and reminders after user confirmation
8. `qa`: answer only from indexed user documents and cite evidence
9. `storage`: encrypted local persistence and deletion/export controls

## Core contract

Every extracted value should carry `value`, `confidence`, `page`, `bbox`, `ocr_text`, and `verification_status`. UI features must not present an extracted fact without an evidence reference.

## Privacy boundary

Default processing and storage remain on-device. Any optional network model must be an explicit, visible mode and must never receive a document silently.
