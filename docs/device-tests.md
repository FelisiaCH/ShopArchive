# Device tests (P00 spike)

Code อยู่ใน `spike/` (ลบทิ้งได้หลังผ่าน: `spike/` + `.github/workflows/spike.yml` + ไฟล์นี้) · ผลของแต่ละ test บันทึกท้ายหัวข้อ

## ได้ไฟล์จากไหน

| ไฟล์ | CI (GitHub → Actions → **spike** → Run workflow) | Build เอง (`cd spike`) |
|---|---|---|
| Server: `spike-server.jar` + `start.bat` + `start.sh` | artifact `spike-server` | `./gradlew :server:dist` → `server/build/dist/` |
| Android APK | artifact `spike-android-apk` | `./gradlew :androidApp:assembleDebug` → `androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| iOS `.ipa` | artifact `ShopArchive-spike-ipa` (zip ที่มี `ShopArchive-spike.ipa`) | build ไม่ได้บน Windows |
| Windows app | — | `./gradlew :app:run` |

- Build เอง: ต้องมี JDK 21 · Android ต้องมี `spike/local.properties` บรรทัดเดียว `sdk.dir=C\:/Users/<ชื่อ>/AppData/Local/Android/Sdk` (ไม่มี SDK → Gradle ข้าม Android เอง)
- Server: `start.bat` (Windows) / `sh start.sh` (Linux) · ตอน start พิมพ์ `PIN (paste into clients): ...` กับ `URL: https://<ip>:8443` · พิมพ์ `status` เพื่อดูซ้ำ · cert อยู่ `certs/` (ลบ = ออกใบใหม่ = pin เปลี่ยน)
- Windows ถาม firewall ครั้งแรก → allow `java` บน **Private network** (มือถือถึง server ผ่าน LAN ได้)
- App บนทุกเครื่อง: ใส่ Base URL + Pin (copy จาก console ส่งเข้ามือถือทาง Line/Notes) → ปุ่ม **HTTPS /ping**, **WS echo** → ✅/❌ + error
- Pin ผิด = แก้ตัวอักษรในสายเดิม 1 ตัว (รูปแบบยังถูก) · ถ้าใส่รูปแบบผิดจะเห็น `pin must be base64...` ซึ่งไม่นับ

## T1 — iOS: ATS + SPKI pin (block P08)

เป้าหมาย: `NSAllowsArbitraryLoads` อย่างเดียว + Ktor Darwin `handleChallenge` ต่อ HTTPS และ `wss://` ไป LAN IP ได้ · pin ผิด fail

Prerequisites: iPhone iOS ≥ 17.4 · AltStore ติดตั้งแล้ว · server รันบน PC · iPhone กับ PC อยู่ Wi-Fi เดียวกัน

1. Download artifact `ShopArchive-spike-ipa` → unzip → ส่ง `ShopArchive-spike.ipa` เข้า iPhone (iCloud Drive/Files) → AltStore → My Apps → **+** → เลือกไฟล์
2. เปิด app · Base URL = `https://<LAN IP ของ PC>:8443` · Pin = จาก console
3. กด **HTTPS /ping** → iOS ถาม Local Network → Allow → ต้องได้ `✅ 200 {"ok":true,...}`
4. กด **WS echo** → ต้องได้ `✅ echo OK: hello ສະບາຍດີ สวัสดี`
5. Negative: แก้ pin 1 ตัว → กดทั้งสองปุ่ม → ต้อง ❌ ทั้งคู่ (จด error text)
6. Negative: Settings → ShopArchive spike → ปิด Local Network → กด **HTTPS /ping** → ต้อง ❌ (จด error text) → เปิดกลับ → ✅
7. (ถ้ามี port forward) ทำข้อ 3–5 ซ้ำกับ `https://<public IP หรือ domain>:8443`

ผ่านเมื่อ: 3 และ 4 ✅ · 5 ❌ ทั้งคู่ · 6 ❌ แล้วกลับเป็น ✅ · ไม่ต้องมี `NSAllowsLocalNetworking`

ผล ☐ ผ่าน ☐ ไม่ผ่าน · วันที่: · เครื่อง/OS: · หมายเหตุ (error text ข้อ 5–6):

## T2 — Android 17: local network + NSD + pin (block P08)

เป้าหมาย: targetSdk 37 ต่อ LAN + NSD ได้ทั้งก่อนและหลัง `ACCESS_LOCAL_NETWORK` · OkHttp TrustManager ตรวจ pin

Prerequisites: emulator Android 17 (API 37) หรือมือถือ Android 17 · server รันบน PC · emulator ใช้ `https://10.0.2.2:8443` · มือถือจริงใช้ LAN IP

1. `adb install -r androidApp-debug.apk` (adb อยู่ `%LOCALAPPDATA%\Android\Sdk\platform-tools`) → เปิด app · ต้องเห็น `targetSdk 37` และ `ACCESS_LOCAL_NETWORK: DENIED`
2. **ก่อน grant**: ใส่ URL + Pin → **HTTPS /ping** → คาดว่า ❌ `ConnectTimeoutException` หลัง 8 วินาที · จดผลจริง
3. **ก่อน grant**: **NSD discover** → คาดว่ามี system dialog "Choose a device to connect" · เลือก `ShopArchive spike` → ต้องเห็น name/host/port/TXT · จดว่าเห็น dialog หรือไม่
4. กด **Request ACCESS_LOCAL_NETWORK** → Allow → label ต้องเป็น `GRANTED`
5. **หลัง grant**: **HTTPS /ping** ✅ · **WS echo** ✅ · **NSD discover** ต้องเห็น `ShopArchive spike @ <ip>:8443 [server-id=..., name=..., version=spike-1]` โดยไม่มี dialog
6. Negative: Pin ผิด → ทั้งสองปุ่มต้อง ❌ `SSLHandshakeException: SPKI pin mismatch`
7. (ถ้ามีมือถือจริง) ทำข้อ 2–6 ซ้ำบนมือถือ + ลอง `adb shell pm revoke xyz.felismp.shoparchive.spike android.permission.ACCESS_LOCAL_NETWORK` เพื่อกลับไปสถานะก่อน grant

ผ่านเมื่อ: ข้อ 5 ทั้งสามอย่างสำเร็จ · ข้อ 6 ❌ · ข้อ 2–3 จดพฤติกรรมก่อน grant (ใช้กำหนด rationale screen ของ P08)

หมายเหตุ Claude smoke test แล้วบน emulator Pixel_10a (API 37.2): ข้อ 2 timeout · ข้อ 3 มี dialog · ข้อ 4–6 ตามคาด — ยังต้องรันเองบนเครื่องจริงเพื่อยืนยัน

ผล ☐ ผ่าน ☐ ไม่ผ่าน · วันที่: · เครื่อง/OS: · หมายเหตุ:

## T3 — Windows: filesystem + Defender + Lao path + JLine/JNA (block P02, P06)

เป้าหมาย: สร้าง/แก้/ย้าย entry folder 1,000 รอบขณะเปิดรูป slip ค้างไว้ · root path อักษรลาว · JNA/JLine native โหลดได้ · console ลาว/ไทย

Prerequisites: เครื่อง dev จริง + Windows Defender เปิดตามปกติ (ห้ามเพิ่ม exclusion) · JDK 21 · Windows Terminal · แอป Photos · ดิสก์ที่จะใช้เป็นที่ตั้ง server จริง

1. Copy `spike-server.jar` `start.bat` `start.sh` ไปโฟลเดอร์ชื่อลาว เช่น `D:\ທົດສອບ ຮ້ານ\`
2. เปิด Windows Terminal → `cd "D:\ທົດສອບ ຮ້ານ"` → `.\start.bat`
3. ตรวจ log: `root=` แสดงลาวถูกต้อง · บรรทัด `Lao sample` / `Thai sample` อ่านออก · `JNA native load OK` · จด `JLine terminal:` (ควรเป็นชื่อ class ไม่ใช่ `none`) · จดทุก `WARNING: Failed to load native library` (Claude เห็น 5 บรรทัดนี้เมื่อรันเป็น pipe ในโฟลเดอร์ลาว เพราะ `System.load` ใช้ ANSI path)
4. พิมพ์ `say ສະບາຍດີ สวัสดี` (พิมพ์ด้วย keyboard/IME จริง) → ต้องสะท้อนกลับเป็นลาว/ไทยอ่านออก + code points `U+0EAA U+0EB0...`
5. `fstest 1000` → เมื่อมี prompt ให้เปิด `slip-1.jpg` ที่พิมพ์ path ไว้ด้วย Photos **ค้างไว้** → กด Enter → รอตาราง summary
6. Negative: เปิด terminal ใหม่ `java -jar spike-server.jar --root "D:\ທົດສອບ"` → ต้องได้ error `--root lost characters` (java.exe บน Windows ทำอักษรลาวใน argv เป็น `?` — Claude ยืนยันแล้ว → P02 หา root จากที่ตั้ง jar/cwd ไม่ใช่ argv)
7. `stop` · ลบ `record\` `tmp\` ทิ้ง

ผ่านเมื่อ: ข้อ 3–4 อ่านออก และ JNA load OK · ข้อ 5 แถว `create: build`, `create: ATOMIC_MOVE`, `held entry.yml` มี **failed = 0** (ok-retry ได้ แต่จดจำนวน + exception class) · แถว `held folder rename A -> B` เป็นข้อมูลอย่างเดียว: ถ้า failed (`AccessDeniedException`) แปลว่า Photos ล็อกทั้ง folder → P06 ห้ามพึ่งการ rename entry folder

ผล ☐ ผ่าน ☐ ไม่ผ่าน · วันที่: · เครื่อง/OS: · หมายเหตุ (ตาราง fstest, `JLine terminal:`, WARNING ข้อ 3):

## T4 — Raspberry Pi 4/5 (optional: ข้ามได้ถ้าไม่ใช้ Pi)

เป้าหมาย: JVM start + Netty + TLS + console + JNA บน arm64

Prerequisites: Pi 4/5 OS 64-bit · SSD เป็นที่วาง root · JDK 21 (`java -version`) · SSH เข้าได้ · locale UTF-8 (`locale` ต้องไม่ใช่ POSIX; ถ้าใช่ `export LANG=C.UTF-8`)

1. Copy `spike-server.jar` `start.sh` เข้าโฟลเดอร์บน SSD → `sh start.sh`
2. ตรวจ log: `JNA native load OK` · `Certificate: created` · จด `JLine terminal:`
3. จากเครื่องอื่นใน LAN: `curl -k https://<pi-ip>:8443/ping` → ต้องได้ JSON
4. เทียบ pin: `echo | openssl s_client -connect <pi-ip>:8443 2>/dev/null | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64` → ต้องเท่ากับ `PIN` ใน console
5. `say ສະບາຍດີ สวัสดี` ใน SSH → อ่านออก
6. `fstest 1000` (กด Enter ทันทีที่ถาม) → `failed = 0` ทุกแถว (แถว rename ควรผ่านบน Linux)
7. `stop`

ผ่านเมื่อ: ข้อ 2–6 ผ่านทั้งหมด

ผล ☐ ผ่าน ☐ ไม่ผ่าน · วันที่: · เครื่อง/OS: · หมายเหตุ:

## T5 — AltStore: Keychain รอด refresh (block P08)

เป้าหมาย: Keychain item (`AfterFirstUnlockThisDeviceOnly`, ไม่มี access group) ยังอยู่หลัง AltStore refresh/re-sign

Prerequisites: iPhone + app ที่ติดตั้งจาก T1 · PC เปิด **AltServer for Windows** (ลง iTunes + iCloud จากเว็บ Apple) · iPhone กับ PC Wi-Fi เดียวกัน · Apple ID เดียวกับที่ติดตั้ง

1. เปิด app → **Write timestamp to Keychain** → จดค่า `Keychain item: <timestamp>`
2. Force-quit แล้วเปิดใหม่ → ค่าเดิมต้องอยู่ (baseline)
3. AltStore → My Apps → **Refresh All** (AltServer ต้องทำงานอยู่) → รอสำเร็จ · จดวันหมดอายุที่รีเซ็ตเป็น 7 วัน
4. เปิด app → ค่า `Keychain item` ต้องเท่าเดิม (ห้ามกด Write ซ้ำ)
5. (แนะนำ) ติดตั้ง `.ipa` ตัวเดิมหรือตัวใหม่ทับผ่าน AltStore **+** โดยไม่ลบ app ก่อน → เปิด app → ค่าต้องเท่าเดิม (จำลอง in-app update ของ P08)
6. (ถ้ามีเวลา) รอให้ครบ 7 วันแล้ว refresh อีกรอบ → ค่าต้องเท่าเดิม

ผ่านเมื่อ: ข้อ 2, 4, 5 ค่าเท่าเดิม · ถ้าข้อ 4/5 กลายเป็น `none` → ไม่ผ่าน (device credential เก็บใน Keychain ไม่ได้ → แก้ decision ก่อน P08)

ผล ☐ ผ่าน ☐ ไม่ผ่าน · วันที่: · เครื่อง/OS: · หมายเหตุ:

## ผลของแต่ละ test block อะไร (จาก P00 Verify)

- T1, T2, T5 → block **P08**
- T3 → block **P02, P06**
- T4 → block การใช้ **Pi** เป็น server
- ข้อไหนไม่ผ่าน → หยุด รอแก้ decision ก่อนเริ่ม phase ที่พึ่งข้อนั้น
