# ShopArchive

อ่าน `docs/plan/MASTER.md` ก่อนเริ่มงาน · plan ห้ามแก้เอง (เสนอแก้ผ่าน audit)

## Principles
- Clean rebuild: ไม่ port logic, UX, math จาก BCN เดิม
- Core ไม่พึ่ง service ภายนอก · data, key, account อยู่ในโฟลเดอร์ jar เท่านั้น
- การเปิด server ออก internet = งานผู้ดูแลแบบ Minecraft (port forward / public IP / tunnel ที่เลือกเอง)
- Paper-style: drop-in jar · YAML config · console · plugins + hotfix
- ข้อมูลห้ามหาย: atomic write + fsync · ไม่มี hard delete · create idempotent · client outbox · copy ไฟล์เดิมก่อน migrate/data fix · backup ก่อน go-live
- Config-first: lock แค่กลไก + invariant · ตัวเลขและนโยบายเป็น config พร้อม default · ค่าไม่ปลอดภัย → clamp + warn
- ใช้งานได้ก่อน · perf/hardening ทีหลัง

## Agent rules
- ทำทีละ phase (หรือ sub-phase) · จบแล้วหยุดรอ audit · ห้าม push
- ห้ามเริ่มงานที่มี ⏳ หรือขึ้นกับ device test ที่ยังไม่ผ่าน
- Stable versions เท่านั้น · pin ใน `libs.versions.toml`
- Library ที่เลือกเอง → บันทึกใน `docs/decisions.md` (ชื่อ, version, เหตุผล)
- ห้าม commit secrets หรือ runtime dirs (`run/`)
- ห้าม dead UI หรือ flag/config ที่ยังไม่มีผล · commit message ต้องตรง diff
- ห้ามเขียน logic ตรงใน route/handler · ผ่าน service ที่ override ได้
- ห้ามเขียนไฟล์นอก root · ห้าม `createTempFile` ที่ไม่ระบุ directory
- ไฟล์ใน `record/` อ่าน/เขียนผ่าน NIO เท่านั้น
- Line endings: `*.sh` = LF · `*.bat` = CRLF
- ตัวเลข/นโยบายใหม่ → config พร้อม default + clamp
- repo BCN เดิม read-only (ใช้เฉพาะ P14)
- ใช้งานได้ก่อน · perf ไม่ใช่เป้าของ v1

## Toolchain
- JDK 21 · `JAVA_HOME` = `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot` · ใช้ `./gradlew` เสมอ
