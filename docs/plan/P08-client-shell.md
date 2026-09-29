# P08 — Client shell
อ่าน: `MASTER.md`, `docs/design-map.md` · Depends: P01, P05 (P08a) · P06 (P08b) · T1, T2, T5 ผ่าน
แบ่ง 2 รอบ audit: P08a → P08b · ทำ UI บน Android/Windows ก่อน · iOS ตรวจผ่าน `.ipa` จาก CI

## P08a — Connect + pairing

### Tasks
- [ ] Server list: หลาย server · 1 server = server-id + หลาย endpoint (LAN auto, IP/domain) · ใช้ LAN ก่อน · สลับ endpoint เองโดยไม่ต้อง unlock ใหม่
- [ ] Discovered (mDNS): Android NSD · iOS `NWBrowser` · Windows jmDNS · เป็นแค่ hint (ต้องผ่าน pin)
- [ ] รับ endpoint ใหม่จาก `/config` + WS
- [ ] Pairing UI:
  - สแกน QR ด้วยกล้องในแอป (Android/iOS · ไม่พึ่ง Google Play services)
  - paste link (ทุก platform) · ไม่ลงทะเบียน URL scheme
  - manual code: address + username + code → แสดง fingerprint → ต้องกดยืนยัน
- [ ] Enroll UI: กรอก/ตั้ง password + PIN · เลือก device mode
- [ ] ตรวจ pin ก่อนส่งข้อมูลใดๆ · ไม่ตรง → block + เตือน
- [ ] Pinning:
  - Android/Windows (OkHttp): `X509TrustManager` ที่ throw เมื่อ SPKI ไม่ตรง + `HostnameVerifier` ที่ตรวจ pin (ห้าม return true เสมอ)
  - iOS (Darwin): `handleChallenge` เทียบ SPKI (เติม ASN.1 header ของ P-256) → `UseCredential` เมื่อตรง · นอกนั้น `Cancel` · ห้าม `PerformDefaultHandling` · ห้ามใช้ preconfigured session · ครอบทั้ง HTTP และ `wss://`
- [ ] iOS Info.plist: `NSAllowsArbitraryLoads = YES` อย่างเดียว (ห้ามใส่ `NSAllowsLocalNetworking`) · `NSLocalNetworkUsageDescription` · `NSBonjourServices` (`_shoparchive._tcp`) · `NSCameraUsageDescription`
- [ ] iOS: local network ถูก deny → พาไป Settings · retry หลัง prompt ครั้งแรก · ห้ามใช้ bundle ID ใน logic (AltStore เปลี่ยนเป็น `<id>.<TEAMID>`) · ไม่มี push notification
- [ ] Android: targetSdk 37 · rationale screen ก่อนขอ `ACCESS_LOCAL_NETWORK` · CAMERA
- [ ] เก็บ device credential: iOS Keychain `…ThisDeviceOnly` (ไม่ตั้ง access group) · Android Keystore-wrapped key + DataStore · Windows Credential Manager/DPAPI
- [ ] Access token อยู่ใน memory เท่านั้น
- [ ] Protocol mismatch → หน้า "Outdated client/server"
- [ ] WS reconnect (backoff) + connection status · reconnect → refetch
- [ ] i18n lo/th/en

### Verify
- iPhone + Android จริง: LAN auto → ออก 4G → สลับไป IP/domain เอง ไม่ต้อง unlock ใหม่
- เปลี่ยน key ที่ server → client block
- Pair ด้วย QR (มือถือ) · link (ทุก platform) · manual code (ทุก platform) ได้

**จบ P08a → หยุดรอ audit**

## P08b — Lock + outbox

### Tasks
- [ ] Device mode:
  - `shared`: รายชื่อ user ที่ pair บนเครื่อง → เลือก → PIN · auto-lock ตาม config · ไม่มี biometrics
  - `personal`: PIN หรือ biometrics (Android BiometricPrompt / iOS LocalAuthentication) ตาม config · Windows ใช้ PIN
- [ ] Re-auth UI (password หรือ PIN) ตามที่ server ขอ
- [ ] Settings: ภาษา · servers/endpoints · devices ของตัวเอง (revoke) · เปลี่ยน PIN/password · lock · ออกจาก server
- [ ] Outbox: สร้าง entry ตอน server ไม่อยู่ → เก็บใน app-private storage (รวม slip) → sync เองด้วย id เดิม
- [ ] Offline unlock (ถ้า config อนุญาต): ตรวจ PIN กับ hash ในเครื่อง → สร้าง entry ได้อย่างเดียว · ส่งพร้อม flag `offline`
- [ ] Badge "pending N"

### Verify
- Shared device: 2 user สลับกันด้วย PIN · auto-lock ทำงาน · biometrics ไม่แสดง
- Server ปิด → สร้าง entry → เปิด server → sync ครบ ไม่ซ้ำ · มี flag `offline`

**จบ P08b → หยุดรอ audit**
