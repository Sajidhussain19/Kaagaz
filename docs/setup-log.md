# Setup log — 2026-10-07

## Checked

- Windows 11 Home Single Language 25H2, build 26200.9457, 64-bit
- ASUS Vivobook TP3402VA, Intel Core i5-13420H, 15.6 GB RAM, Intel UHD Graphics (about 2 GB reported shared adapter memory)
- C: drive: 154.0 GB free; battery: 82%, discharging, estimated 81 minutes at audit time
- Hypervisor present; firmware virtualization API reported disabled. WSL 2 is installed with only the `docker-desktop` distribution running.
- Git 2.53.0, Node 24.14.0, npm 11.9.0, Docker 29.2.0, Compose 5.0.2, VS Code 1.139.1
- GitHub CLI, Java/JDK, Gradle, Android Studio/SDK/ADB/emulator were not available before installation attempts
- Python 3.14.3 and pip 25.3 run, but Python reports a broken real-location lookup; a created venv cannot bootstrap pip
- WinGet records mention Python 3.12.10, but the Python launcher cannot find or execute it
- Android-related environment variables and default SDK/cache locations
- Vivo OfficeKit installation records and official Vivo download source
- Python AI/ML package presence without printing credentials
- Git repository state and a conservative secret filename audit
- HTTPS connectivity to GitHub and Google download services passed
- No connected Android/ADB/MTP device was detected
- Docker CLI works, but the Docker Desktop Linux engine was not accessible during the audit
- npm and pip caches contained no dependency payloads

## Changed

- Created the Kaagaz starter repository structure and documentation
- Added defensive `.gitignore` rules for secrets, keystores, builds, virtual environments, dependencies, private documents, and model files
- Added and ran a dependency-free Python pipeline unit test successfully
- Created initial local commit `17ef960`; no remote repository was created or pushed

## Installation status

- A WinGet installation batch for GitHub CLI, OpenJDK 17, Vivo OfficeKit, and Android Studio was started from official manifests.
- WinGet/MSI remained running without completing, and none of the four packages appeared as installed in the final package query. The processes were left untouched to avoid corrupting an active installation.

## Not completed or not verifiable without the user

- Phone pairing, USB-debugging authorization, file transfer, mirroring, clipboard, and remote-control tests
- Normal reboot test
- Android build/install/launch test (requires completed Android Studio/SDK installation and a device or emulator)
- Vivo OfficeKit launch and feature tests (installation did not complete)
- Android Studio launch and SDK setup (installation did not complete)
- Python 3.12 repair/reinstall and clean venv creation
