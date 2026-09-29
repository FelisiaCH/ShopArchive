# P05 — Network + auth (pairing)
อ่าน: `MASTER.md`, `research/` (ส่วน Login) · Depends: P04
แบ่ง 2 รอบ audit: P05a → P05b

## P05a — TLS + pairing

### Tasks
- [ ] ลงทะเบียน node `shoparchive.devices.pair`
- [ ] CLI `--port` (override `server.properties`)
- [ ] Cert ECDSA P-256 (BouncyCastle) สร้างตอน first run → `certs/` · SAN: LAN IPs + domain ใน config · ใกล้หมดอายุหรือ IP เปลี่ยน → ออกใบใหม่ด้วย key เดิม (มีผลตอน restart) · validity ≤ 825 วัน
- [ ] `status` เพิ่ม: fingerprint (SPKI SHA-256), connections
- [ ] `GET /api/v1/info` (public): server-id, name, motd, version, protocol
- [ ] Header `PROTOCOL_VERSION` ทุก request + WS handshake · ไม่ตรง → error code เฉพาะ
- [ ] Body size limit + timeouts
- [ ] mDNS `_shoparchive._tcp` (jmDNS · instance ต่อ interface · ข้าม VPN/virtual adapter) เมื่อ `lan-discovery=true` · TXT: server-id, name, version
- [ ] `ban-ip` / `pardon-ip` + `banned-ips.json`
- [ ] `config/shoparchive.yml` → `auth:` เพิ่ม:
  - `pairing`: ttl 10m · manual-code-attempts 5 · manual-code on · sources [console, admin, self]
  - `pin.length` 6
  - `password`: required-for [op, shoparchive.users.manage, shoparchive.branches.manage, shoparchive.devices.revoke] · min 8 · min-op 15 · max ≥ 64
  - `session.access-token` 15m · `reauth-window` (นาทีที่ถือว่ายืนยันล่าสุดยังใช้ได้)
  - clamp ทุกค่า · invariant ตาม `MASTER.md` ห้ามเป็น config
- [ ] First run ไม่มี user → console แนะนำ `user add <name>` → `op <name>` → `user pair <name>`
- [ ] Console `user pair <name>` · `user add` แสดง pairing ให้ทันที
  - QR (Unicode half-block) + link + manual code + fingerprint · แสดงแบบ terminal only
  - `--png` → สร้างไฟล์ใน `tmp/` · ลบเมื่อใช้แล้วหรือหมดอายุ
- [ ] Payload: server-id, SPKI SHA-256, secret 128 bit, username, endpoint ≤2 · manual code 10 ตัวจาก alphabet ของ RFC 8628 · pending อยู่ใน memory เท่านั้น
- [ ] `POST /api/v1/pair/redeem` (public): ใช้ครั้งเดียว · หมดอายุตาม config · manual code ผิดครบ → code ใช้ไม่ได้ → ได้ enrollment token (ทำได้แค่ขั้น enroll)
- [ ] Enroll:
  - password: มีแล้ว → ต้องกรอก · ไม่มีแต่ต้องมี (`required-for`) → ตั้งใหม่
  - PIN: มีแล้ว → ต้องกรอก · ไม่มี → ตั้งใหม่
  - เลือก device mode `shared` / `personal` → ออก device credential
- [ ] Password: normalize NFC ก่อน hash · blocklist (list พื้นฐาน + ชื่อร้าน/สาขา/username) · Argon2id + semaphore จำกัดจำนวน hash พร้อมกัน · hash หลอกเมื่อไม่มี user นั้น
- [ ] Device credential: 256 bit CSPRNG · เก็บ SHA-256 ใน `data/devices/<device-id>.yml` (label, platform, mode, users: credential hash, created, last-used, pin-failures) · key = (device, user)
- [ ] `POST /api/v1/unlock`: device credential + PIN หรือ password → access token (memory เท่านั้น) · บันทึกเวลายืนยันล่าสุด
- [ ] Re-auth สำหรับ action สำคัญ: เวลายืนยันล่าสุดต้องอยู่ใน `reauth-window` · ไม่งั้นขอ PIN/password ใหม่
- [ ] API สร้าง pairing: ให้คนอื่นต้องมี `shoparchive.devices.pair` · ให้ตัวเองได้ถ้า `sources` มี `self` · ต้อง re-auth
- [ ] WS `/api/v1/ws`: auth ตอน handshake · heartbeat · event กรองตามสิทธิ์ + branch · `say` → broadcast
- [ ] Pair สำเร็จ → event + แจ้งเตือนเครื่องอื่นของ user
- [ ] Pair / unlock / fail log → `data/audit/yyyy/mm/dd.log`

### Verify
- Tests ด้วย Ktor `testApplication`
- ไม่มี credential → 401 ทุก endpoint ยกเว้น `/info` + redeem
- Pairing ใช้ซ้ำ / หมดอายุ / code ผิดครบ → ใช้ไม่ได้
- Enrollment token เรียก API อื่นไม่ได้
- User เดิม pair โดยไม่รู้ password/PIN เดิม → ไม่ผ่าน
- Secret จาก `user pair` ไม่อยู่ใน `logs/`
- mDNS เห็นจากเครื่องอื่นใน LAN

**จบ P05a → หยุดรอ audit**

## P05b — Policy + API

### Tasks
- [ ] ลงทะเบียน node: `shoparchive.users.manage`, `shoparchive.devices.revoke`, `shoparchive.server.status`
- [ ] `auth:` เพิ่ม:
  - `device`: idle-expiry 90d · auto-lock shared 3m / personal 15m · biometrics-personal on
  - `session`: reauth-every 30d · reauth-idle 14d
  - `pin.max-failures` 10
  - `backoff`: start 1s · max 15m · disable-at 100
  - `reauth-actions`: เปลี่ยน password/PIN · pair/revoke device · เปลี่ยน role · ลบ entry · export
  - `offline-create` on
- [ ] PIN ผิดครบ → revoke user นั้นบนเครื่องนั้น
- [ ] Backoff ต่อ account · ผิดครบ `disable-at` → disable · console `user unlock <name>`
- [ ] Rate limit ต่อ IP (Ktor RateLimit · 429 + `Retry-After`) เฉพาะ public endpoints · ไม่มี auto ban
- [ ] Re-auth ตามรอบ: ครบ `reauth-every` หรือ idle เกิน `reauth-idle` → บังคับ password (ถ้ามี) หรือ PIN
- [ ] Device ไม่ได้ใช้เกิน `idle-expiry` → credential หมดอายุ
- [ ] Console `user reset <name>`: revoke ทุกเครื่อง + ล้าง password/PIN + แสดง pairing ใหม่
- [ ] Disable user / revoke device / เปลี่ยน password / เปลี่ยน role → credential ใช้ไม่ได้ทันที + WS หลุด
- [ ] Devices API: list ของตัวเอง · revoke (ของคนอื่นต้อง `shoparchive.devices.revoke`) · เปลี่ยน mode
- [ ] Admin API (`shoparchive.users.manage`): users list/create/enable/disable/role/branch/perm (ยกเว้น op/deop) · ผ่าน service เดียวกับ console
- [ ] `GET /api/v1/config`: server-driven config (auth policy ฝั่ง client, endpoints, currencies) · P06 เพิ่ม branches + categories
- [ ] Endpoint list อัปเดตผ่าน `/config` + WS เมื่อ IP เปลี่ยน
- [ ] `status` เพิ่ม devices ที่ online
- [ ] Permission + branch scope check ทุก endpoint

### Verify
- PIN ผิดครบ → user นั้นบนเครื่องนั้นถูก revoke · เครื่องอื่นไม่กระทบ
- Backoff ทำงาน · ครบ `disable-at` → disable · `user unlock` ปลดได้
- Revoke / disable → WS หลุด + credential ใช้ไม่ได้ทันที
- Interceptor `pre-auth` (ลงจาก test) block request → 403 ก่อนถึง auth
- ค่า config นอก range → clamp + warn

**จบ P05b → หยุดรอ audit**
