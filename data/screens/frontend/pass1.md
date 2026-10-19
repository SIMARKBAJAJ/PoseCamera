# Frontend emulator pass 1

Package/activity: com.example.posecamera/.MainActivity (debug reads `-e route gallery|home`; `am force-stop` first, otherwise intent is just delivered to the running instance).

## Commands that worked (Git Bash; set MSYS_NO_PATHCONV=1 or /sdcard paths get mangled)
- Emulator (none was running; I started it, then `adb emu kill`):
  `ANDROID_AVD_HOME=D:/coding/toolchains/android-avd D:/coding/toolchains/android-sdk/emulator/emulator.exe -avd SahiRep_API_36 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot` (boots in about 1 min; poll `adb shell getprop sys.boot_completed`)
- Emulator APK: `JAVA_HOME='D:\coding\toolchains\jdk17\jdk-17.0.20.1+1' cmd /c ".\\gradlew.bat --offline assembleDebug -PsahirepBaseUrl=http://10.0.2.2:5000/"`; copy the apk out before rebuilding default. Rebuilt plain afterwards; BuildConfig BASE_URL verified = http://192.168.1.16:5000/.
- Backend: copy instance/sahirep.db to a scratch path; in backend/: `SAHIREP_DATABASE_URI=sqlite:///C:/.../verify.db SAHIREP_JWT_SECRET=<40 rand> .venv/Scripts/python.exe -m waitress --listen=127.0.0.1:5000 --call app:create_app`
- Register: POST /api/auth/register `{"username","password","consent":true}`. Username must be alphanumeric only (^[A-Za-z0-9]+$), so no underscores or emails.
- Fresh state: `adb shell pm clear com.example.posecamera` then `pm grant ... CAMERA`.
- Login via UI: tap username field (540,1050), `input text`, tap password (540,1260), `input text`, keyevent 4 to hide keyboard, tap LOG IN (540,1486). Worked first time; home shows "Synced".
- Tabs (y=2200): home x=109, train 324, library 539, progress 756, you 972.
- Kill backend: `taskkill /PID <pid> /F` (single slash under MSYS_NO_PATHCONV=1).

## Screenshots
01-04 gallery (top + 3 scroll steps); 05 home with leftover data from a previous install (streak 1, "1 sets, 0 reps", logged-in session persisted by `install -r`); 06-09 tabs pre-clear; 10 auth screen, 11 filled, 12 after login; 13 home empty + logged in (streak 0, "Abhi koi session nahi", Synced); 14-17 tabs logged in.

## Findings
- Auth screen is fine to drive (2 EditTexts, LOG IN, "New here? Create an account").
- YOU tab shows only "Coming next" (placeholder; no logout/account UI reachable).
- TRAIN tab first shows a "Before you start" disclaimer with "I understand"; the camera screen itself was not exercised.
- Streak label: "1 DAY" vs "0 DAYS" ok.
- Home with real data (PRs, plan, last session with sets) is unverified; only the empty state and the 1-set leftover state were seen. No safe UI path to log sets without running a lift.
- Screens 02-04, 06-11, 14-17 were captured but not individually reviewed visually by me.

## Jank (swiftshader software GL on PC; NOT representative of phones)
- Gallery, 8 scroll swipes: 340 frames, 47 janky (13.82%), p90 53 ms, p95 57 ms, p99 73 ms.
- Home idle ~10 s (logged in): 644 frames, 0 janky (0%), p90 31 ms, p95 32 ms.
- Home idle ~10 s (earlier, tab switch cycle): 843 frames, 17 janky (2.02%), p90 34 ms, p95 38 ms.
- Note the gallery is animation-heavy and scroll-driven; its jank is the one to re-check on a real device.

## Crashes
`adb logcat -d | grep -c FATAL` = 0. Only boot-time AndroidRuntime START lines.
