# P12 — Release + backup
อ่าน: `MASTER.md` · Depends: P11

## Tasks: release
- [ ] Version: semver + build number · bump `PROTOCOL_VERSION` เฉพาะ breaking API
- [ ] Server: `shoparchive-server.jar` + `shoparchive-server-x.y.z.zip` (jar, `start.sh`, `start.bat`, service templates systemd + WinSW, `plugins/shoparchive-telegram.jar`, README)
- [ ] README ตามหัวข้อ Ops ใน `MASTER.md` + first run (`user add` → `op` → `user pair`) + firewall (พอร์ต HTTPS + UDP 5353) + `plugins approve`
- [ ] Windows: MSI (bundled JRE) · ไม่ sign · README: SmartScreen
- [ ] Android: APK (sideload) + AAB
- [ ] iOS: unsigned `.ipa` จาก CI (tag) · README AltStore (Apple ID ของร้านต่อเครื่อง, Developer Mode, AltServer for Windows ต่อสาขา, refresh 7 วัน, งดอัปเดต iOS จนกว่า AltStore ยืนยัน) · trigger ย้ายไป $99/ปี
- [ ] In-app update: วางไฟล์ใน `downloads/` → `GET /api/v1/updates` (ต้อง auth) → client แจ้งเวอร์ชันใหม่
  - Android / Windows: ดาวน์โหลด + ติดตั้งจากในแอป
  - iOS: ดาวน์โหลด `.ipa` ผ่านช่องที่ pin → share sheet → AltStore (ห้ามใช้ `altstore://` deep link)

## Tasks: backup (เงื่อนไขก่อน go-live)
- [ ] Console `backup`: pause writer → zip snapshot → `backups/<ts>.zip` (ทุกอย่างใน root ยกเว้นรูป slip, `cache/`, `logs/`, `tmp/`, `libraries/`, `versions/`, `backups/`, `exports/`)
- [ ] รูป slip mirror แบบ incremental → `backups/slips/<sha256>.jpg`
- [ ] Config `backup:` enabled, เวลารันทุกวัน, keep N ชุด, `copy-to` (path ที่สอง)
- [ ] `copy-to` ใช้ไม่ได้ (ถอด USB) → warn · ไม่ crash
- [ ] `status` แสดง backup ล่าสุด + ผล
- [ ] README: ขั้นตอน restore (stop → แตก zip ลงโฟลเดอร์ใหม่ → copy slips กลับ → start)

## Verify
- Windows VM สะอาด: ติดตั้ง MSI → pair → ใช้งานได้
- วาง APK ใหม่ใน `downloads/` → Android แจ้งอัปเดต
- iPhone จริง: `.ipa` ผ่าน AltStore → pair → ใช้งานได้ · อัปเดตผ่าน share sheet ได้
- Backup → ลบ root → restore ตาม README → ตัวเลข dashboard + รูป slip ครบ
- Server zip บน Linux (WSL2) + Windows: start ได้ทั้งคู่

**จบ phase → หยุดรอ audit**
