# Kaagaz Grand Finale Judge Checklist

## Demo artifacts

- Primary tested APK: `output/demo/Kaagaz-demo-debug-tested.apk`
- Exact size: `51,380,159 bytes`
- SHA-256: `9F36B3329AC650224DEF2C4AF39B296917507CF4D013B858D4524975BB046F14`
- Install without clearing existing data: `adb install -r output\demo\Kaagaz-demo-debug-tested.apk`
- Fictional demo PDF: `output/pdf/kaagaz-fictional-demo-policy.pdf`
- Device destination: `/sdcard/Download/kaagaz-fictional-demo-policy.pdf`
- Copy to the device if needed: `adb push output\pdf\kaagaz-fictional-demo-policy.pdf /sdcard/Download/kaagaz-fictional-demo-policy.pdf`

Use only the fictional PDF. Do not open personal documents during the demo.

## Device preparation

- Charge the phone above 80% and keep a charger nearby.
- Set screen timeout to at least 10 minutes; disable battery saver for the presentation.
- Set comfortable brightness and disable Do Not Disturb if showing notification settings.
- Confirm Kaagaz notification permission is allowed before demonstrating reminders.
- Copy the fictional PDF to Downloads before enabling airplane mode.
- Enable airplane mode to demonstrate offline processing; verify the local PDF remains visible in Downloads.
- Keep USB debugging connected as a recovery option.
- Launch Kaagaz and leave it on the privacy-first home screen.
- Keep the protected debug APK available; do not use the unsigned release APK.

## Eight-step, five-minute demo

1. **Privacy-first opening:** Show the home screen and the “Private by design” card. Explain that files and OCR stay on the device.
2. **Import:** Tap **Add a document**, choose `kaagaz-fictional-demo-policy.pdf` from Downloads, and show the PDF preview.
3. **On-device OCR:** Tap **Extract text** (or **Run OCR**) and wait for completion. Show the retained page text briefly.
4. **Structured review:** Open **Review extracted fields**. Point out the seven insurance fields and the Supported / Needs review / Not found labels.
5. **Evidence and correction:** Open the expiry field, show its highlighted source line, edit the value, and point out that editing does not confirm it. Use **Confirm this value** only after reviewing the source.
6. **Grounded Q&A:** Open **Ask Kaagaz** and ask: “When does my policy expire?” Show the answer status, then tap its evidence to return to the highlighted source.
7. **Local reminder:** Return to the summary, tap **Set expiry reminder**, choose **7 days before**, review the source/status, and save. Show “Scheduled locally.”
8. **Persistence:** Return home, close and relaunch Kaagaz, reopen the saved PDF, and show that the correction, confirmation, Q&A conversation, and reminder configuration remain available.

## Recovery during the demo

- **PDF selection:** Cancel the picker, reopen **Add a document**, choose **Downloads**, and select the exact fictional filename. If missing, use the `adb push` command above.
- **OCR:** Wait for the current page to finish. If an error appears, return to the document and use **Run OCR again** on the known-good fictional PDF. Do not switch documents mid-run.
- **Q&A:** Use a supported question exactly: “When does my policy expire?”, “What is my policy number?”, “How much premium is listed?”, or “Who is the insured person?” Unsupported questions intentionally return an honest limitation message.
- **Reminder:** Confirm the date is in the future, the value is document-supported or explicitly user-confirmed, and notification permission is allowed. Reopen the reminder screen to inspect saved status. Do not promise exact delivery time.
- **App issue:** Relaunch Kaagaz first. If necessary, reinstall the protected APK with `adb install -r`; never use `-d`, `-u`, uninstall, or clear app data during the demo.

## Verified and untested claims

Verified on the physical device:

- Image/PDF import, bundled on-device OCR, structured extraction, evidence navigation, editing/confirmation, deterministic Q&A, private persistence, and local reminder creation.
- Saved fictional document and reminder survived a real device reboot.
- A pending WorkManager reminder remained registered after reboot.
- The APK requests neither `INTERNET` nor `ACCESS_NETWORK_STATE`.

Do not claim:

- Exact-to-the-minute reminder delivery; Android may defer background work.
- A future notification was observed; delivery was not waited for.
- General natural-language understanding; Q&A uses supported deterministic question patterns.
- Cloud sync, cross-device restore, production release signing, or Play Store readiness.
- Perfect OCR or extraction accuracy for arbitrary documents.

## Final pre-demo check

- [ ] Protected APK hash matches the value above.
- [ ] Fictional PDF is readable and present in Downloads.
- [ ] Phone battery is above 80%, screen timeout is 10+ minutes, and brightness is comfortable.
- [ ] Notification permission is allowed and battery saver is off.
- [ ] Airplane mode is on after the PDF is copied locally.
- [ ] Kaagaz opens to the home screen and the saved-document library is intact.
- [ ] The fictional demo PDF opens, OCR/summary/evidence/Q&A screens are available, and the reminder screen shows its saved state.
- [ ] No personal documents or notifications are visible.
- [ ] USB cable, ADB, protected APK, and PDF are available for recovery.
