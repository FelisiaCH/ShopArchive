# P10 — Plugin loader
อ่าน: `MASTER.md` · Depends: P09

Service registry, interceptors, events, commands, scheduler มีอยู่แล้วใน core (P03) · phase นี้เปิดให้ jar ภายนอกใช้

## Tasks
- [ ] Loader `plugins/*.jar` · `plugin.yml`: `name`, `version`, `main`, `api-version`, `depend`, `softdepend`
- [ ] Classloader แยกต่อ plugin · parent = `shoparchive-api` + Kotlin stdlib, coroutines, serialization · internals ของ core ไม่ถูก expose
- [ ] ปฏิเสธ jar ที่มี `kotlin/` หรือ `kotlinx/` ข้างใน · ปฏิเสธ plugin ที่ compile ด้วย Kotlin ใหม่กว่า host
- [ ] api-version ไม่ตรง → ไม่โหลด · depend หาย/วน → error
- [ ] Approval: jar ใหม่หรือเปลี่ยน → ไม่โหลดจนกว่า `plugins approve <name>` (เก็บ SHA-256 ใน `plugins/approved.yml`) · config `plugins.require-approval` (default on)
- [ ] Lifecycle load → enable → disable · throw ตอน enable → disable plugin นั้น, server ไปต่อ
- [ ] Data folder `plugins/<Name>/` · `saveDefaultConfig()` copy `config.yml` จาก jar · `reloadConfig()` · console `reload <plugin>`
- [ ] Secret ใน plugin config → mask ใน log + `plugins`
- [ ] Routes `/api/v1/x/<plugin>/…` (ต้อง auth)
- [ ] Permission node ของ plugin → เข้า `permissions.txt` + ไฟล์ user/role (กลไกจาก P04)
- [ ] Data fix: one-time บน core data · copy ไฟล์ที่จะแก้ไป `data/datafix/<plugin>-<id>/` ก่อน · marker ในโฟลเดอร์เดียวกัน · log
- [ ] `plugins/update/*.jar` → แทนที่ตอน start (ต้อง approve ใหม่)
- [ ] CLI `--no-plugins` = safe mode
- [ ] `plugins` command + Admin server status: list · สถานะ · service ที่ override
- [ ] Template Gradle สำหรับ plugin (`compileOnly` shoparchive-api + Kotlin version ตรง host)

## Verify
- ไม่มี plugin → core ครบ
- Plugin override service → ใช้ของ plugin · ลบ jar → กลับเป็น core
- 2 plugins priority เท่ากัน → ไม่ start, error ชัด
- Plugin throw ตอน enable → ถูก disable, server ไม่ล่ม
- Jar ที่ shade Kotlin → ถูกปฏิเสธ · jar ยังไม่ approve → ไม่โหลด
- `plugins/update/x.jar` → แทนที่ตอน restart
- Data fix → มีไฟล์เดิมเก็บไว้ · restart → ไม่รันซ้ำ

**จบ phase → หยุดรอ audit**
