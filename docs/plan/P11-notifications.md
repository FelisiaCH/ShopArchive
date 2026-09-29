# P11 — Notifications
อ่าน: `MASTER.md` · Depends: P06, P10

## Tasks
- [ ] `shoparchive-api`: `Notifier` service · `Notification` (event type, entry data, branch, user, locale)
- [ ] Core default = log only
- [ ] Outbox `data/outbox/<id>.yml` + retry (backoff) ใน core · plugin implement แค่ `deliver()` · fail ถาวร → แสดงใน `status` + Admin
- [ ] Config ของ plugin ไม่ครบ/ผิดตอน enable → ไม่ register + warn → ใช้ notifier ถัดไป
- [ ] `plugins/telegram` (ship กับ release): bot token (ออกใหม่), chat IDs, ภาษา, events ที่แจ้ง, templates
- [ ] Template placeholders: `{type}`, `{category}`, `{item}`, `{amount}`, `{currency}`, `{branch}`, `{user}`, `{time}`
- [ ] เพิ่ม channel = plugin ฟัง event เอง (ส่งคู่กันได้)
- [ ] `plugins/example-discord` (webhook): replace Notifier + config folder + templates · ใช้เป็น test + template · ไม่ ship

## Verify
- ลง Discord → ข้อความเข้า Discord ไม่เข้า Telegram · ลบ jar → กลับเข้า Telegram
- Webhook ว่างตอน start → warn + ใช้ Telegram · core ไม่ล่ม
- Channel ล่มชั่วคราว → ส่งครบ ไม่ซ้ำ

**จบ phase → หยุดรอ audit**
