# P13 — Hotfix patches (ชั้น 2)
อ่าน: `MASTER.md`, `research/` (ส่วน Hotfix) · Depends: P02, P10, P12

## Tasks
- [ ] Classloader ของ ShopArchive (จาก P02) apply bytecode patch ตอน class load · ใช้ ASM หรือ Mixin fork ที่ยัง maintain (FabricMC) · ไม่ใช้ Java agent
- [ ] Core โหลดหลังอ่าน hotfix jar เสมอ · class ของ hotfix อยู่ใน loader ของ core (ไม่ใช่ loader แยกแบบ plugin)
- [ ] Target ได้เฉพาะ method ที่ core ติด `@HotfixTarget` + `@JvmName` (ชื่อคงที่ · ไม่ใช่ `inline`/`suspend`/value-class mangled)
- [ ] Patch pin กับ SHA-256 ของ class เป้าหมาย · inject แล้ว match 0 จุด → fatal
- [ ] `plugin.yml`: `hotfix: true`, `fixes: [ID]`, `severity: security|bug`, `target-build`
- [ ] Core jar ฝัง build number + `fixed-issues` (ID ที่ merge แล้ว)
- [ ] ตอน start:
  - ID อยู่ใน `fixed-issues` → ข้าม + log ว่า core แก้แล้ว ลบ plugin ได้
  - ยังไม่ fixed + build/hash ไม่ตรง: `security` → ไม่ start (ข้ามได้ด้วย `--ignore-hotfix <ID>` + warn ทุก start) · `bug` → warn + ข้าม
- [ ] Hotfix โหลดก่อน plugin อื่น · 2 patch ที่ method เดียว → ไม่ start + บอกชื่อ
- [ ] CLI `--no-patches`, `--ignore-hotfix <ID>`
- [ ] `plugins` + `status` + Admin: hotfix ที่ active / ที่ core แก้แล้ว (ควรลบ)
- [ ] `docs/fixes.md`: ID · severity · hotfix plugin · build ที่ merge
- [ ] Fix workflow ใน `CLAUDE.md`: hotfix → audit → deploy → merge เข้า core + เพิ่ม ID ใน `fixed-issues` → release → hotfix retire เอง

## Verify
- Patch method ที่ติด `@HotfixTarget` ใน test target → ทำงาน
- Class เป้าหมายเปลี่ยน → patch ไม่ถูก apply · security → ไม่ start
- ID อยู่ใน `fixed-issues` → hotfix ถูกข้าม + log
- `--ignore-hotfix` → start + warn · bug patch ไม่ตรง → warn + start ปกติ

**จบ phase → หยุดรอ audit**
