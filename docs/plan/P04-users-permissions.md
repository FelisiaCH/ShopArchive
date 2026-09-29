# P04 — Users & permissions
อ่าน: `MASTER.md` · Depends: P03

## Tasks
- [ ] กลไก node + ไฟล์ user/role (ทดสอบด้วย test node) · core node ลงทะเบียนใน phase ที่ใช้งานจริง:
  - P05: `shoparchive.users.manage` (false) · `shoparchive.devices.pair` (false) · `shoparchive.devices.revoke` (false) · `shoparchive.server.status` (false)
  - P06: `shoparchive.entry.create` (true) · `shoparchive.entry.view.own` (true) · `shoparchive.entry.view.all` (false) · `shoparchive.entry.edit.own` (true) · `shoparchive.entry.edit.all` (false) · `shoparchive.entry.delete.own` (true) · `shoparchive.entry.delete.all` (false) · `shoparchive.branch.all` (false) · `shoparchive.branches.manage` (false) · `shoparchive.categories.manage` (false) · `shoparchive.export` (false)
  - P07: `shoparchive.dashboard.view` (false)
- [ ] `user/<username>.yml`: `file-version`, `id`, `display-name`, `enabled`, `op`, `role`, `branches`, `locale`, `password` (Argon2id หรือ null), `pin` (Argon2id หรือ null), `permissions` (ครบทุก node)
- [ ] `user/role/<name>.yml`: `file-version`, `display-name`, `permissions` (ครบทุก node)
- [ ] Username `[a-z0-9_]` 3–32 · ห้าม `role` · ชื่อ role ห้าม `none`
- [ ] Resolve: op → true ทุกตัว · มี role → ค่าจาก role · `role: none` → ค่าของ user
- [ ] User มี role → server เขียน block `permissions` = copy จาก role (read-only) ทุก start/reload/เปลี่ยน role · แก้มือ → ถูกทับ + warn
- [ ] Node ใหม่ → เติมค่า default + comment `# ใหม่` ทุกไฟล์ · node ที่หายไป → เก็บไว้ + warn
- [ ] `user/permissions.txt` generate ทุก start/reload: node + คำอธิบาย (ตาม `locale`, fallback en) + default · header บอกว่าแก้ไม่มีผล
- [ ] `password`/`pin` ในไฟล์ไม่ใช่ hash → ใช้ไม่ได้ + warn
- [ ] Console: `user add|list|info|enable|disable|rename|role|branch` · `perm <user> <node> true|false` · `perm search <kw>` · `role create|list|perm` · `op` · `deop` · `reload users` · tab complete ชื่อ node
- [ ] `perm` บน user ที่มี role → error บอกให้แก้ role หรือ `user role <u> none`
- [ ] `user role <u> none` → copy ค่าจาก role มาเป็นของ user · `user role <u> <role>` → log ค่าเดิม
- [ ] First run ไม่มี user → console แนะนำ `user add <name>` → `op <name>`
- [ ] เขียนไฟล์ atomic + เช็ค mtime ก่อนเขียน (reload ก่อนถ้าถูกแก้มือ) · `user/` chmod 700, ไฟล์ 600 (Unix)
- [ ] Migrate `file-version` ผ่าน framework จาก P03

## ตัวอย่าง `user/somchai.yml` (หลัง P07 · ไม่มี role · ใช้ PIN อย่างเดียว)
```yaml
file-version: 1
id: 3f2a9c1e-…
display-name: "ສົມໃຈ"
enabled: true
op: false
role: none
branches: [main, market]
locale: lo
password: null
pin: "$argon2id$v=19$m=47104,t=1,p=1$…"
permissions:
  shoparchive.entry.create: true
  shoparchive.entry.view.own: true
  shoparchive.entry.view.all: false
  shoparchive.entry.edit.own: true
  shoparchive.entry.edit.all: false
  shoparchive.entry.delete.own: true
  shoparchive.entry.delete.all: false
  shoparchive.dashboard.view: true
  shoparchive.export: false
  shoparchive.branch.all: false
  shoparchive.branches.manage: false
  shoparchive.categories.manage: false
  shoparchive.users.manage: false
  shoparchive.devices.pair: false
  shoparchive.devices.revoke: false
  shoparchive.server.status: false
```

## Verify
- `user add noy --role cashier --branch market` → ไฟล์ถูกสร้างถูกต้อง
- `op noy` → ทุกสิทธิ์
- แก้ role file → `reload users` → user ใน role เปลี่ยน + block mirror อัปเดต
- `perm` บน user ที่มี role → error
- ลงทะเบียน test node ใหม่ → ทุกไฟล์มี node + `# ใหม่` · `permissions.txt` มี node ใหม่
- แก้ไฟล์มือแล้วยังไม่ reload → แก้ผ่าน console → ไม่ทับของที่แก้มือ

**จบ phase → หยุดรอ audit**
