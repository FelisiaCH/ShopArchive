# P03 — Config, console, logging, service registry
อ่าน: `MASTER.md` · Depends: P02

## Tasks: config
- [ ] kaml (YAML 1.2) · pin version · ใช้ทั้ง config และ data
- [ ] Config renderer: อ่านไฟล์ → เติม default → ถ้ามีการเปลี่ยน copy ไฟล์เดิมไป `data/migration/<ts>/` → เขียนใหม่จาก template ในโค้ด (comment ครบ) · header บอกว่า comment ของผู้ดูแลไม่ถูกเก็บ · key ที่ไม่รู้จัก → warn
- [ ] Clamp: ทุก key มี range · นอก range → ใช้ค่าที่ใกล้สุด + warn
- [ ] `server.properties`: `server-name`, `motd`, `port`, `bind-address`, `lan-discovery`, `log-level`
- [ ] `config/shoparchive.yml`: `timezone` (default `Asia/Vientiane` · ใช้ตัดวัน log และ business date), `locale` · section อื่นเพิ่มใน phase ที่ใช้
- [ ] `config/currencies.yml`: LAK (exponent 0), THB (2), USD (2)
- [ ] ลำดับค่า: CLI flag > ไฟล์ > default
- [ ] Migration framework (config + data files): `config-version` / `file-version`

## Tasks: console + logging
- [ ] Log4j2 + TerminalConsoleAppender: colored levels · `logs/latest.log` · rotate รายวัน `.log.gz` · path อิง root
- [ ] ช่องทาง "terminal only" สำหรับ secret (ไม่ลง `logs/`)
- [ ] Console (เมื่อมี TTY): tab complete · `help`, `version`, `status`, `stop`, `reload`
- [ ] `status`: uptime, memory (phase ถัดไปเพิ่มข้อมูลของตัวเอง)
- [ ] Watchdog: task ใน scheduler/executor ค้างเกินค่าใน config → thread dump ลง log
- [ ] Fatal error → `crash-reports/<ts>.txt`

## Tasks: service registry (interfaces ใน `shoparchive-api` · implementation ใน core)
- [ ] Service registry: core ลง default · override ด้วย priority · priority ชนกัน → ไม่ start + บอกชื่อ
- [ ] Interceptors: `pre-auth` → `pre-handle` → `post-handle` (แก้ input, short-circuit, แก้ output)
- [ ] Events: priority, cancellable, sync/async
- [ ] Commands + tab complete · scheduler (sync/async, delay, repeat)
- [ ] Permission node registry (node, description en บังคับ + th/lo optional, default)
- [ ] Core ลง service / command / node ผ่าน registry แบบเดียวกับที่ plugin จะใช้

## Verify
- ลบ key ใน config → start → เติมกลับ + comment จาก template ครบ
- ค่านอก range → clamp + warn
- `config-version` เก่า → migrate ถูก + ไฟล์เดิมอยู่ใน `data/migration/`
- `reload` → config ใหม่มีผลโดยไม่ restart
- Secret ที่ส่งทาง terminal only ไม่อยู่ใน `logs/`
- แกล้งให้ค้าง → มี thread dump
- Unit tests: registry priority, interceptor order, event cancel

**จบ phase → หยุดรอ audit**
