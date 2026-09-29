# P01 — Design spike
อ่าน: `MASTER.md` · Depends: P00

## Tasks
- [ ] Felisia วางดีไซน์ลง `design/` (source + screenshots desktop/mobile)
- [ ] Inventory → `docs/design-map.md`: screens, components, states, tokens · 1 screen = 1 sub-phase ใน P09
- [ ] Flow ที่ไม่มี mockup → สร้างจาก design system · ติดป้าย "ไม่มี mockup" ใน design-map:
  - pairing (สแกน QR / paste link / manual code + ยืนยัน fingerprint)
  - PIN + สลับ user · server list + endpoints
  - outbox pending
  - Admin: users, devices, categories, branches, export, server status
- [ ] Field/dimension ที่ดีไซน์ต้องใช้แต่ domain ยังไม่มี → เสนอแก้ `MASTER.md` ก่อน P06
- [ ] Tokens → Compose theme (colors, typography, spacing, shapes) ใน `composeApp`
- [ ] Fonts ตามดีไซน์ + Noto Sans Lao/Thai → Compose resources (bundle ในแอป · ห้ามโหลดจาก internet)

## Verify
- design-map ครบทุก screen ในดีไซน์ + ทุก flow ข้างบน
- Theme preview ตรงดีไซน์ รวมข้อความลาว/ไทย บน Android, Windows และ iOS (`.ipa` จาก CI)

**จบ phase → หยุดรอ audit**
