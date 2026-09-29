# P00 — Scaffold + device-test spike
อ่าน: `MASTER.md` · Depends: —

## Tasks: environment (Windows)
- [ ] Repo `FelisiaCH/ShopArchive` (public · Apache-2.0) ที่ `E:\Project\ShopArchive` · `git init` + remote · เปิด long paths (Windows `LongPathsEnabled` + `git config core.longpaths true`)
- [ ] Claude Code รันบน Windows (Git Bash) · WSL2 (Ubuntu) ใช้ทดสอบ server บน Linux เท่านั้น
- [ ] JDK 21 (Temurin) · Android Studio + emulator Android 17
- [ ] `.gitattributes`: `* text=auto eol=lf` · `*.bat eol=crlf`
- [ ] Plan อยู่ใน `docs/plan/` แล้ว · commit เข้า repo · ห้ามแก้ plan เอง (เสนอแก้ผ่าน audit)

## Tasks: scaffold
- [ ] Gradle monorepo (Kotlin DSL) + `gradle/libs.versions.toml` · stable versions เท่านั้น
- [ ] Modules:
  - `shared` (jvm, android, iosArm64, iosSimulatorArm64)
  - `shoparchive-api` · `server-launcher` · `server`
  - `composeApp` (android library, ios, desktop → package เฉพาะ Windows) · `androidApp` (Android application · AGP 9) · `iosApp` (Xcode project · build บน CI)
  - `plugins/telegram`, `plugins/example-discord`, `plugins/import` (ว่างไว้)
- [ ] `run/` = server root ตอน dev
- [ ] `.gitignore`: `run/`, build outputs, IDE, `local.properties`, secrets
- [ ] `CLAUDE.md` = Principles + Agent rules จาก `MASTER.md`
- [ ] `docs/decisions.md`: library ที่เลือก (ชื่อ, version, เหตุผล)
- [ ] CI (GitHub Actions):
  - ubuntu x64: server tests + Android build
  - windows: server tests + MSI
  - macos: unsigned `.ipa` · รันเฉพาะ manual dispatch หรือ tag
  - Linux arm64 (optional · runner arm64 หรือ Pi จริง): server tests
- [ ] CI iOS: Kotlin `embedAndSignAppleFrameworkForXcode` + `xcodebuild archive` แบบ `CODE_SIGNING_ALLOWED=NO` → zip `Payload/` → `.ipa` เป็น artifact
- [ ] CI: i18n parity (lo, th, en key ครบ)

## Tasks: device-test spike (`spike/` · ลบได้หลังผ่าน)
Claude Code เขียน · Felisia รัน · บันทึกผลใน `docs/device-tests.md`
- [ ] Spike server: Ktor HTTPS + WS · cert ECDSA P-256 self-signed · พิมพ์ SPKI SHA-256 ตอน start · `GET /ping` + WS echo
- [ ] Spike clients: Android app · iOS app · Windows JVM app · ใส่ pin (SPKI) ได้ · ต่อ HTTPS + WS · แสดงผลสำเร็จ/ล้มเหลว
- [ ] **T1 iOS** (iPhone จริง ≥ 17.4 ผ่าน AltStore): `NSAllowsArbitraryLoads` อย่างเดียว + Ktor Darwin `handleChallenge` ตรวจ SPKI → HTTPS + `wss://` ไป LAN IP (public IP/domain ทดสอบได้เมื่อมี port forward) · pin ผิด → ต้อง fail
- [ ] **T2 Android 17** (emulator ได้ · targetSdk 37): LAN + NSD ทั้งตอนมีและไม่มี `ACCESS_LOCAL_NETWORK` · OkHttp TrustManager ตรวจ pin
- [ ] **T3 Windows** (เครื่อง dev + Defender): สร้าง/ย้าย entry folder 1,000 รอบขณะเปิดรูป slip ค้างไว้ · root path อักษรลาว · JLine/JNA native load · console ลาว/ไทยใน Windows Terminal
- [ ] **T4 Raspberry Pi 4/5** (64-bit, SSD · ข้ามได้ถ้าไม่ใช้ Pi): JVM start + Netty + TLS + console
- [ ] **T5 AltStore**: ติดตั้ง `.ipa` จาก CI · refresh ผ่าน AltServer for Windows แล้ว Keychain item ยังอยู่

## Verify
- `./gradlew build` ผ่านบน Windows + CI ubuntu · CI เขียว
- `docs/device-tests.md` มีผล T1–T5 · ข้อไหนไม่ผ่าน → หยุด รอแก้ decision
  - T1, T2, T5 → block P08 · T3 → block P02, P06 · T4 → block การใช้ Pi เป็น server

**จบ phase → หยุดรอ audit**
