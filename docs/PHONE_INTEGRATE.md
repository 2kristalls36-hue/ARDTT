# Phone integrate build (`0.4.0-phone-integrate`)

Merges PR #8 (AWG Direct), #9 (native Bypass), #10 (create-call), #11 (dial path).

## Build

```bash
./scripts/build-bypass-client.sh
cd android && ./gradlew :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`  
(`jniLibs/*.so` and `libwg-go` are local build artifacts — not committed.)

## Smoke on device

1. Install debug APK, import provision profile (test VPS peer/password).
2. Path B: save call hash (manual or **Создать звонок**), Connect → TUN `10.9.0.x`.
3. Path A: Connect Direct with AWG keys from the same profile.
4. Settings → dial Авто / vkcalls / Капча.
