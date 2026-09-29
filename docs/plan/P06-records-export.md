# P06 — Records + export
อ่าน: `MASTER.md`, `docs/design-map.md` · Depends: P05 · T3 ผ่าน

## Tasks: domain
- [ ] ลงทะเบียน node: `shoparchive.entry.*` (7 ตัวตาม P04), `shoparchive.branch.all`, `shoparchive.branches.manage`, `shoparchive.categories.manage`, `shoparchive.export`
- [ ] `config/shoparchive.yml` เพิ่ม: `edit-window` (default: business day เดียวกัน), `slips` (max-count, max-size)
- [ ] Services ใน registry (override ได้): `EntryService`, `EntryValidator`, `EntryStore`, `SlipStore`, `BranchService`, `CategoryService`, `ExportService`
- [ ] Validation ใน `shared` (client + server ใช้ร่วม): ≥ 1 tender · amount > 0 · currency อยู่ใน `currencies.yml` · slip iff มี tender online · branch อยู่ใน scope · category มีอยู่และใช้กับ type นั้นได้ · จำนวน/ขนาด slip ตาม config
- [ ] `data/branches.yml`: `key` (slug, เปลี่ยนไม่ได้), `display-name`, `archived` · API + console (`shoparchive.branches.manage`) · branch key ในไฟล์ user ที่ไม่มีอยู่จริง → warn
- [ ] `data/categories.yml`: `key` (เปลี่ยนไม่ได้), ชื่อ lo/th/en, `applies-to` (income / expense / both), `archived` · API + console (`shoparchive.categories.manage`)
- [ ] `GET /api/v1/config` เพิ่ม branches + categories ตามสิทธิ์
- [ ] `edit-window` (config): แก้/ลบของตัวเองได้ภายใน business day · เกิน → ต้องมี `*.all`
- [ ] รับ entry ที่มี flag `offline` เฉพาะเมื่อ `auth.offline-create` เปิด
- [ ] Entry จาก outbox offline → history ติด flag `offline`
- [ ] `docs/domain.md`: model + นิยาม totals (net, เงินสดเข้า-ออกสุทธิ ต่อ currency) + unit tests

## Tasks: storage
- [ ] `record/yyyy/mm/dd/<uuidv7>/`: `entry.yml`, `history.yml`, `slip-N.jpg`
- [ ] Create: multipart (entry + slips) → stage `tmp/<id>/` → atomic rename · มี `<id>` อยู่แล้ว → คืนตัวเดิม
- [ ] Edit: `entry.yml` temp → fsync → rename + append history
- [ ] Move date = rename โฟลเดอร์ · soft delete = `deleted: true` · ไม่มี hard delete
- [ ] Single writer สำหรับทุกการเขียน
- [ ] Windows: retry ทุก rename/move (backoff 1ms → 2s) · อ่าน/เขียนผ่าน NIO เท่านั้น · fsync directory เฉพาะ Unix
- [ ] Recovery scan ตอน start: ของค้างใน `tmp/` · id ซ้ำ 2 ที่ · path ไม่ตรงกับ id/date → broken แค่ entry นั้น + log
- [ ] Manifest (mtime + size + checksum) ใน `cache/` · แก้มือ → validate → `manual-edit` + diff ลง history
- [ ] Amount = decimal string ตาม exponent · YAML 1.2 · ข้อความ quote เสมอ
- [ ] Migrate `file-version` ผ่าน framework จาก P03

## Tasks: API + export
- [ ] `POST /api/v1/entries`
- [ ] `GET /api/v1/entries?from&to&branch&type&currency&category` (ช่วงวันที่บังคับ · default เดือนนี้)
- [ ] `GET|PUT|DELETE /api/v1/entries/{date}/{id}` · `POST …/move` · `GET …/slips/{n}` · `GET …/history`
- [ ] `GET /api/v1/export?from&to&branch` → CSV stream (`shoparchive.export` + re-auth)
- [ ] Console `export <from> <to> [branch]` → `exports/<ts>.csv`
- [ ] CSV: UTF-8 BOM · 1 แถวต่อ tender · columns: date, time, entry id, type, branch, category, item, note, currency, method, amount, created-by, slip count · default ไม่รวม entry ที่ลบ (option รวมได้)
- [ ] Events `EntryCreated/Updated/Deleted/Moved` → WS

## ตัวอย่าง `entry.yml`
```yaml
file-version: 1
id: 0192a6f4-7c1e-7b3a-9f10-2a3b4c5d6e7f
date: 2026-09-27
type: expense
branch: market
category: supplies
item: "ນ້ຳກ້ອນ"
note: ""
created-by: { id: 0b7c55de-…, name: noy }
created-at: "2026-09-27T08:14:03+07:00"
updated-at: "2026-09-27T08:14:03+07:00"
deleted: false
tenders:
  - currency: LAK
    method: cash
    amount: "150000"
  - currency: THB
    method: online
    amount: "120.50"
slips:
  - file: slip-1.jpg
    sha256: "3fa9…c21"
```

## Verify
- ส่งซ้ำด้วย id เดิม → ได้ตัวเดิม ไม่มีโฟลเดอร์ใหม่
- kill ระหว่างสร้าง → ไม่มีโฟลเดอร์ครึ่งๆ ใน `record/` · `tmp/` ถูกจัดการตอน start
- Move + kill กลางทาง → entry อยู่วันเดียว
- Online ไม่มี slip → reject · category ไม่ตรง type → reject
- แก้ `entry.yml` มือ → `manual-edit` + diff · ทำให้เสีย → broken แค่ตัวนั้น
- Staff branch A สร้าง/ดู branch B → 403
- Staff แก้ของเมื่อวาน → 403 · admin แก้ได้ + มี history
- Windows + Defender: create/move 1,000 รอบ → ไม่มี error หลุดถึง client
- Export: เปิดใน Excel และ Google Sheets แล้วลาว/ไทยถูก · ยอดรวมตรงกับ dashboard

**จบ phase → หยุดรอ audit**
