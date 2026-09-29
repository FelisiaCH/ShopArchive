# P07 — Cache + dashboard API
อ่าน: `MASTER.md`, `docs/design-map.md` · Depends: P06

## Tasks
- [ ] ลงทะเบียน node `shoparchive.dashboard.view`
- [ ] `cache/summary/yyyy/mm/dd.yml` + `cache/summary/yyyy/mm.yml`: branch × currency × type × method × category → sum + count
- [ ] Incremental ใน single writer ทุก create/edit/delete/move · soft-deleted ไม่นับ · type ที่ไม่รู้จัก → ข้าม + warn
- [ ] `cache-version` · bump → rebuild ทั้งหมด
- [ ] Start: verify/rebuild ให้เสร็จก่อน `Done` · console แสดง progress
- [ ] `GET /api/v1/dashboard?from&to&branch&currency`:
  - totals · เงินสดเข้า-ออกสุทธิ ต่อ currency
  - series รายวัน · breakdown ตาม category และ method
  - delta เทียบช่วงก่อนหน้า · recent entries
  - ต้องมี `shoparchive.dashboard.view` + กรองตาม branch scope
- [ ] WS `summary.updated {date, branch}`
- [ ] Recent items ต่อ category (suggestion ตอนกรอก item) จาก cache

## Verify
- Property test: create/edit/delete/move แบบสุ่ม → incremental = rebuild เต็ม
- ลบ `cache/` → start → ตัวเลขเท่าเดิม
- Staff branch A ไม่เห็นตัวเลข branch B

**จบ phase → หยุดรอ audit**
