# ShopArchive — MASTER (draft)

Self-hosted server แบบ PaperMC (`shoparchive-server.jar`) + client apps: Android · iOS · Windows
Repo: `FelisiaCH/ShopArchive` (public · Apache-2.0) · `E:\Project\ShopArchive` · plan อยู่ที่ `docs/plan/`
สถานะ: draft · ยังไม่มีโค้ด · 1 phase = 1 ไฟล์ · decision log: `GRILL.md` · research: `research/` (อ้างเลข phase ชุดก่อน grill)

## ⏳ Open
- ดีไซน์ → `design/` (P01)
- ผล device test (P00) · ข้อไหนไม่ผ่าน → แก้ decision ก่อนเริ่ม phase ที่พึ่งข้อนั้น
- เช็ค CGNAT ที่สายของเครื่อง server (งานผู้ดูแล · ไม่ block โค้ด)

## Principles
- Clean rebuild: ไม่ port logic, UX, math จาก BCN เดิม
- Core ไม่พึ่ง service ภายนอก · data, key, account อยู่ในโฟลเดอร์ jar เท่านั้น
- การเปิด server ออก internet = งานผู้ดูแลแบบ Minecraft (port forward / public IP / tunnel ที่เลือกเอง)
- Paper-style: drop-in jar · YAML config · console · plugins + hotfix
- ข้อมูลห้ามหาย: atomic write + fsync · ไม่มี hard delete · create idempotent · client outbox · copy ไฟล์เดิมก่อน migrate/data fix · backup ก่อน go-live
- Config-first: lock แค่กลไก + invariant · ตัวเลขและนโยบายเป็น config พร้อม default · ค่าไม่ปลอดภัย → clamp + warn
- ใช้งานได้ก่อน · perf/hardening ทีหลัง

## Stack
- Kotlin ทั้งหมด · Gradle monorepo (Kotlin DSL) + `gradle/libs.versions.toml` · stable versions เท่านั้น
- Server: Ktor (Netty) · JDK 21+ · รับรอง Windows x64 + Linux x64/arm64 (รวม Raspberry Pi 4/5 64-bit) · macOS ไม่รับรอง
- Clients: Compose Multiplatform — Android, iOS, Windows desktop · Navigation + ViewModel + Koin
- Min: Android 8 (API 26) · iOS 17.4 (ขั้นต่ำของ AltStore) · Windows 10
- iOS targets: `iosArm64` + `iosSimulatorArm64` เท่านั้น
- Dev: Windows (Claude Code บน Git Bash · repo บน NTFS) · iOS build บน CI (macOS runner) เท่านั้น
- Package: `xyz.felismp.shoparchive`
- Storage: ไฟล์ทั้งหมด · ไม่มี DB
- YAML: kaml (YAML 1.2) ทั้ง config และ data · config เขียนจาก template ในโค้ด
- Crypto: BouncyCastle (cert ECDSA P-256, Argon2id)
- Logging: Log4j2 + TerminalConsoleAppender (JLine)

## Repo modules
- `shared`: DTOs, validation, error codes, WS events, API client, `PROTOCOL_VERSION`, `DEFAULT_PORT`
- `shoparchive-api`: interfaces ของ service registry, interceptors, events, commands, scheduler, permission nodes · core ใช้ตั้งแต่ P03 · plugin ใช้ตั้งแต่ P10
- `server-launcher`: launcher แบบ Paperclip
- `server`: core
- `composeApp` (android library, ios, desktop → package เฉพาะ Windows), `androidApp` (Android application · AGP 9 แยกจาก KMP), `iosApp`: clients
- `plugins/telegram`, `plugins/example-discord`, `plugins/import`

## Root layout (โฟลเดอร์ของ jar)
- `shoparchive-server.jar`, `server.properties`, `start.sh`, `start.bat`, `banned-ips.json`
- `config/`: `shoparchive.yml`, `currencies.yml`
- `user/`: `permissions.txt`, `role/<name>.yml`, `<username>.yml`
- `record/yyyy/mm/dd/<entry-id>/`: `entry.yml`, `history.yml`, `slip-N.jpg`
- `data/`: `branches.yml`, `categories.yml`, `devices/`, `audit/`, `outbox/`, `datafix/`, `migration/`, `server-id`, `session.lock`
- `cache/`: summaries + manifest (ลบได้ · rebuild เอง)
- `certs/`: keystore
- `plugins/`: jars · `update/` · `approved.yml` · `<Name>/config.yml`
- `exports/`, `backups/`, `logs/`, `crash-reports/`, `downloads/`, `tmp/`, `libraries/`, `versions/`

## Locked decisions

### Runtime
- Root = โฟลเดอร์ของ jar (resolve จาก jar location ไม่ใช่ cwd) · `--root` ใช้ตอน dev (Windows: ASCII เท่านั้น · argv ที่ไม่ใช่ ANSI กลายเป็น `?`)
- Windows: root path มีอักขระนอก ASCII → warn ตอน start (JDK-8195129: `System.load` จาก path unicode ไม่ได้ → native lib ไม่โหลด) · แนะนำ root ASCII ใน README
- Start ผ่าน `start.sh` / `start.bat` / service template → ไม่มีไฟล์ถูกเขียนนอก root (script ส่ง JVM flags ย้าย tmp, perf data, crash log เข้า root)
- `start.bat` ตั้ง console เป็น UTF-8 · แนะนำ Windows Terminal
- Portable: stop → copy ทั้งโฟลเดอร์ = ย้าย server (Windows ↔ Linux ได้)
- `server.properties` = network/boot · `config/` = พฤติกรรม core · `user/`, `record/`, `data/` = business data
- `config-version` / `file-version` + auto-migrate · key ใหม่เติม default · ค่าเดิมไม่หาย · copy ไฟล์เดิมไป `data/migration/<ts>/` ก่อน migrate
- Config เขียนใหม่จาก template: comment จาก template ครบ · comment ของผู้ดูแลไม่เก็บ · key ที่ไม่รู้จัก → warn
- เปลี่ยน plugin = restart · `reload` = config เท่านั้น
- Filesystem FAT32/exFAT/network share → ไม่ start · root บน SD card → warn

### Network
- HTTPS พอร์ตเดียว ใช้ทั้ง LAN + internet
- Cert self-signed ใบเดียว (ECDSA P-256) สร้างตอน first run · ต่ออายุด้วย key เดิม (มีผลตอน restart) · validity ≤ 825 วัน
- Client เชื่อ server ผ่าน SPKI pin ที่ได้จาก pairing เท่านั้น · ไม่มี TOFU
- Identity = `data/server-id` · 1 server หลาย endpoint (LAN + IP/domain) · client ใช้ endpoint ไหนก็ได้ที่ key ตรง pin · LAN ก่อน
- mDNS `_shoparchive._tcp` (TXT: server-id, name, version) = แค่ hint
- Endpoint ที่ไม่ต้อง auth: `/api/v1/info`, pairing redeem เท่านั้น · ไม่มี remote console

### Users & permissions
- 1 user = 1 ไฟล์ `user/<username>.yml` · username `[a-z0-9_]` 3–32 · ชื่อลาว/ไทยใน `display-name` · ห้ามใช้ `role`
- สร้างผ่าน console (`user add`) หรือ Admin ในแอป
- `op: true` = ทุกสิทธิ์ · `op` / `deop` จาก console เท่านั้น
- ไฟล์ user มีครบทุก node · ค่า `true` / `false`
- Role optional: `user/role/<name>.yml` · มี role → สิทธิ์จาก role 100% (block ใน user = copy read-only) · `role: none` → ใช้ค่าของ user · ชื่อ role ห้ามใช้ `none`
- `user/permissions.txt` generate ทุก start/reload
- Branch scope ตาม `branches` · `shoparchive.branch.all` = ทุก branch
- Disable user แทนการลบ

### Login (pairing)
- เครื่องใหม่เข้าระบบได้ทางเดียว: pairing (QR / link / manual code)
- สร้าง pairing ได้จาก: console (`user add`, `user pair`) · หน้า Admin · เครื่องของ user เองที่ pair แล้ว (ตาม config)
- Pairing มี: server-id, SPKI pin, secret ≥128 bit, username, endpoint ≤2 · ใช้ครั้งเดียว · อายุตาม config · pending อยู่ใน memory เท่านั้น
- Manual code ≥40 bit · ผิดได้ตาม config · ต้องกดยืนยัน fingerprint
- Redeem → enrollment: user เดิมกรอก password/PIN เดิม · user ใหม่ตั้ง password (ถ้าต้องมี) + PIN → device credential
- Device credential: opaque 256 bit · server เก็บ hash · ต่อคู่ (device, user) → access token อายุสั้นใน memory
- ใครต้องมี password = config `auth.password.required-for` · คนที่เหลือใช้ PIN อย่างเดียว
- PIN รายคน ใช้ได้ทุกเครื่อง · server เป็นคนตรวจ · นับครั้งผิดต่อ (user, device) · ผิดครบ → revoke user นั้นบนเครื่องนั้น
- Device mode: `shared` (หลาย user · สลับด้วย PIN · auto-lock · ไม่มี biometrics) / `personal` (1 user · PIN หรือ biometrics บนมือถือ)
- Re-auth: ครบรอบ / idle / ก่อน action สำคัญ · ใช้ password ถ้ามี ไม่งั้น PIN
- Offline (server ไม่อยู่): ตรวจ PIN ในเครื่องได้เฉพาะการสร้าง entry ลง outbox · entry ติด flag `offline`
- Backoff ต่อ account · ผิดครบ → disable → `user unlock` · ไม่มี auto IP ban
- Recovery: `user reset <name>` → revoke ทุกเครื่อง + ล้าง password/PIN + pairing ใหม่
- Pair / revoke / disable / เปลี่ยน password / เปลี่ยน role → มีผลทันที + WS หลุด + แจ้งเตือนเครื่องอื่นของ user
- Invariant (ห้ามเป็น config): pairing ต้องมี SPKI pin · ไม่มีโหมด TOFU · ขนาด secret/code ขั้นต่ำ · ใช้ครั้งเดียว · secret ไม่ลง log · credential เก็บเป็น hash
- ค่าทั้งหมดอยู่ใน `config/shoparchive.yml` → `auth:` (default ดู P05)

### Records
- 1 entry = 1 โฟลเดอร์ `record/yyyy/mm/dd/<uuidv7>/` · date = business date (`timezone` ใน config)
- Type v1: `income` / `expense` · type เพิ่มได้ในอนาคต (type ที่ไม่รู้จัก: summary ข้าม + warn)
- `category` บังคับ เลือกจาก `data/categories.yml` (ต้องใช้กับ type นั้นได้) · `item` พิมพ์เอง + suggestion
- Slip บังคับ iff มี tender online
- Amount ในไฟล์ = decimal string ตาม exponent (LAK 0, THB 2, USD 2) · ภายใน = minor units
- ID = UUIDv7 สร้างที่ client → create idempotent
- Soft delete · `history.yml` append-only · แก้มือได้ (ตรวจเจอ → `manual-edit` + diff)
- Currency แยกกัน ไม่แปลง FX

### Dashboard
- อ่านจาก `cache/summary/` เท่านั้น · incremental ทุกการเขียน · rebuild ได้ทุกเมื่อ
- Dimension: branch × currency × type × method × category
- ใช้คำว่า "เงินสดเข้า-ออกสุทธิ" ไม่ใช่ "Cash on Hand"

### Export
- CSV ตามช่วงวันที่ + สาขา · 1 แถวต่อ tender · UTF-8 BOM
- Console `export` → `exports/` · Admin → ดาวน์โหลดลงเครื่อง · ต้องมี `shoparchive.export` + re-auth

### Plugins (P10, P13)
- ชั้น 1: override points (service registry, interceptors, events, commands, routes) · core ใช้ registry ตั้งแต่ P03
- ชั้น 2: hotfix bytecode patch (P13)
- Plugin = code เต็มสิทธิ์ · jar ใหม่ต้อง approve ก่อนโหลด (config) · ลงเฉพาะ jar ที่ build + audit เอง
- Patch ได้แค่ server · client ปรับผ่าน server-driven config (data เท่านั้น)

### Notifications (P11)
- `Notifier` service · core default = log · outbox + retry ใน core · Telegram plugin = default channel

### Clients
- Screens ตามดีไซน์ (`design/` → `docs/design-map.md`) · screen ที่ไม่มี mockup สร้างจาก design system + ติดป้ายใน design-map
- ทำ UI บน Android/Windows ก่อน · iOS ตรวจผ่าน `.ipa` จาก CI
- i18n: lo, th, en
- Outbox: สร้าง entry ได้ตอน server ไม่อยู่ (create only)
- iOS: ATS exception `NSAllowsArbitraryLoads` อย่างเดียว + ตรวจ SPKI pin เองใน `handleChallenge` (ขึ้นกับผล T1)
- ไม่พึ่ง Google Play services

### Distribution
- iOS: unsigned `.ipa` จาก CI → AltStore sideload · ย้ายไป Apple Developer Program $99/ปี เมื่อมีสาขาที่ไม่มีเครื่องเปิด AltServer หรือแอปหมดอายุระหว่างขายครั้งแรก
- Android: APK sideload + AAB
- Windows: MSI (bundled JRE) · ไม่ sign
- In-app update ผ่าน `downloads/` · iOS ส่ง `.ipa` เข้า AltStore ผ่าน share sheet

## Ops (ผู้ดูแล server · อยู่ใน README)
- เครื่องเปิด 24/7 · UPS ครอบเครื่อง + router/ONT
- Raspberry Pi: รุ่น 4/5 · OS 64-bit · root บน SSD
- Windows: ตั้ง Defender exclusion ที่ root · เปิด server ใน Windows Terminal
- เช็ค CGNAT จาก WAN IP ในหน้า router: `100.64.x.x`–`100.127.x.x` = CGNAT · `10.x` / `172.16–31.x` / `192.168.x` = double NAT · อื่น = public
- Public IP → port forward พอร์ตเดียว · ทดสอบจาก 4G · fingerprint ต้องตรงกับ console
- CGNAT → ขอ public/fixed IP จาก ISP หรือใช้ tunnel ที่เลือกเอง
- iOS: Apple ID ของร้าน 1 ตัวต่อ iPhone (ห้ามใช้ของ dev) · Developer Mode · เครื่อง Windows เปิด AltServer for Windows ตลอด (iTunes + iCloud จากเว็บ Apple) 1 เครื่องต่อสาขาที่มี iPhone · refresh ทุก 7 วัน · งดอัปเดต iOS จนกว่า AltStore ยืนยันว่ารองรับ

## Agent rules (→ `CLAUDE.md`)
- ทำทีละ phase (หรือ sub-phase) · จบแล้วหยุดรอ audit · ห้าม push
- ห้ามเริ่มงานที่มี ⏳ หรือขึ้นกับ device test ที่ยังไม่ผ่าน
- Stable versions เท่านั้น · pin ใน `libs.versions.toml`
- Library ที่เลือกเอง → บันทึกใน `docs/decisions.md` (ชื่อ, version, เหตุผล)
- ห้าม commit secrets หรือ runtime dirs (`run/`)
- ห้าม dead UI หรือ flag/config ที่ยังไม่มีผล · commit message ต้องตรง diff
- ห้ามเขียน logic ตรงใน route/handler · ผ่าน service ที่ override ได้
- ห้ามเขียนไฟล์นอก root · ห้าม `createTempFile` ที่ไม่ระบุ directory
- ไฟล์ใน `record/` อ่าน/เขียนผ่าน NIO เท่านั้น
- Line endings: `*.sh` = LF · `*.bat` = CRLF
- ตัวเลข/นโยบายใหม่ → config พร้อม default + clamp
- repo BCN เดิม read-only (ใช้เฉพาะ P14)
- ใช้งานได้ก่อน · perf ไม่ใช่เป้าของ v1

## Phases
- P00 Scaffold + device-test spike
- P01 Design spike
- P02 Launcher + runtime
- P03 Config, console, logging, service registry
- P04 Users & permissions
- P05 Network + auth (P05a TLS + pairing · P05b policy + API)
- P06 Records + export
- P07 Cache + dashboard
- P08 Client shell (P08a connect + pairing · P08b lock + outbox)
- P09 Screens
- P10 Plugin loader
- P11 Notifications
- P12 Release + backup
- P13 Hotfix patches
- P14 Import BCN เดิม (optional)

## Later
- Desktop macOS / Linux · macOS server
- Web client (Kotlin/Wasm, LAN-only) · relay + `relay-sources` · private CA · ACME · UPnP
- Passkeys (rejected จนกว่าจะมี public domain + paid Apple) · PAKE · device-key signing · TOTP สำหรับ op · Windows Hello
- Transfer + บัญชีเงิน + ยอดยกมา · FX · Budgets/Reports · Printer plugin
- App Store / TestFlight · update check (optional) · perf tuning · offline edit
