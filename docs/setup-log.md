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

## Second audit — 2026-10-07

### Installed and verified

- Microsoft OpenJDK 17.0.20.1: `java` and `javac` launch successfully; `JAVA_HOME` is set.
- Android Studio 2026.2: installed from `Google.AndroidStudio`; `studio64.exe` starts as a responsive process.
- Android SDK at `C:\Users\sajid\AppData\Local\Android\Sdk`:
  - Android 16 / API 36 platform revision 2
  - Build-Tools 36.1.0
  - Platform-Tools / ADB 37.0.1
  - Command-line tools 23.0 (`sdkmanager` available; Android CLI 1.0.16500706)
  - SDK licenses accepted; no emulator or system image installed
- Vivo OfficeKit 6.8.2: installed from Vivo's official publisher endpoint; its main window starts and responds. A separate window titled `Error` also appeared, so feature readiness is not claimed.
- Python 3.12.10 and pip 25.0.1: `py -3.12` works; a temporary virtual environment was created, verified, and deleted.
- GitHub CLI 2.102.0: executable works; no GitHub host is authenticated.

### Environment changes

- Set per-user `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the standard Android SDK path.
- Added SDK `platform-tools` and command-line tools to the per-user PATH.
- Set `PY_PYTHON=3.12` for the Python launcher.

### Remaining limitations

- Bare `python` still resolves to Python 3.14.3 because `C:\Python314` precedes user paths in the machine-wide PATH. The attempted non-destructive machine PATH reorder was denied; no Python installation was removed or renamed.
- OfficeKit phone pairing and device features remain unverified without the physical iQOO phone.
- Android Studio's executable launch was verified, but native-window inspection was unavailable through the Windows control helper.
- Standalone `gradle` is not installed. Android SDK build-tools are ready; a project-specific Gradle wrapper will be generated with the first Android project.
