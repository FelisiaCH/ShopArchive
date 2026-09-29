# P14 — Import BCN เดิม (optional)
อ่าน: `MASTER.md` · Depends: P06, P10
v1 ไม่ import · Sheets เป็น archive อ่านอย่างเดียว · ทำ phase นี้เมื่อ Felisia สั่งเท่านั้น

## Tasks
- [ ] อ่าน repo BCN เดิม (read-only) → `docs/legacy.md`: Sheets columns · row-per-tender → entry · slip reference (Drive)
- [ ] Export: Sheets → CSV/JSON · Drive slips → folder
- [ ] `plugins/import`: console `import-bcn <dir>` · dry-run default, `--apply` · idempotent · branch name → key · item เดิม → category (mapping ใน config)
- [ ] เขียนผ่าน `EntryService` เป็น entry folder ปกติ (history: `imported`)
- [ ] Report: count + sum ต่อ branch × currency × เดือน เทียบ Sheets

## Verify
- Report ตรง 100% · รันซ้ำ = 0 entry ใหม่

**จบ phase → หยุดรอ audit**
