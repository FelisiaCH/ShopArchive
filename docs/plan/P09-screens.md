# P09 — Screens
อ่าน: `MASTER.md`, `docs/design-map.md` · Depends: P07, P08

1 screen = 1 sub-phase (P09a, P09b, …) ตามลำดับใน `design-map.md`

## ทุก screen ต้องมี
- [ ] ตามดีไซน์ทั้ง desktop + mobile · screen ที่ไม่มี mockup ใช้ design system + ติดป้ายใน design-map
- [ ] ซ่อน/disable ตามสิทธิ์ + branch scope
- [ ] Loading, empty, error states
- [ ] i18n lo/th/en ครบ
- [ ] Realtime ผ่าน WS

## Screens ขั้นต่ำ
- [ ] Entry:
  - type, branch, category (บังคับ · กรองตาม type), item (+ suggestion ต่อ category), note
  - tenders: currency × Cash/Online + amount · เพิ่ม currency ได้
  - slips: camera/gallery (mobile), file picker (desktop) · compress JPEG ที่ client · upload progress
  - ส่งไม่สำเร็จ → draft ค้าง · retry ด้วย id เดิม · server ไม่อยู่ → เข้า outbox
  - ใช้เฉพาะถ้าอยู่ในดีไซน์: chips ×10/×100/×1000, thousands separator, category popup, confirm sheet
- [ ] Dashboard: `/api/v1/dashboard` · "เงินสดเข้า-ออกสุทธิ" · breakdown ตาม category · badge pending จาก outbox
- [ ] Records: list + filter (ช่วงวันที่บังคับ, branch, type, currency, category) · edit/delete/move ตามสิทธิ์ + re-auth ตาม config · history
- [ ] Admin:
  - users (ยกเว้น op) · toggle permission disable เมื่อ user มี role + แสดง "จาก role X"
  - devices: list, mode, revoke · สร้าง pairing (QR / link / code) ให้ user
  - branches · categories
  - export CSV: เลือกช่วง + สาขา → re-auth → save dialog (desktop) / share sheet (mobile)
  - server status (`shoparchive.server.status`): connections, fingerprint

**จบแต่ละ sub-phase → หยุดรอ audit**
