# P02 — Launcher + runtime
อ่าน: `MASTER.md` · Depends: P00 (T3 ผ่าน)

## Tasks
- [ ] `server-launcher` stage 1: compile ด้วย toolchain เก่า (bytecode Java 8) · Java < 21 → error ชัดเจน
- [ ] Stage 1 ตั้ง property ก่อน library โหลด → `<root>/tmp/…`: `jna.tmpdir`, `jline.tmpdir`, `jansi.tmpdir`, `io.netty.native.workdir`, `io.netty.tmpdir`
- [ ] Stage 2: extract core + libraries → `versions/<build>/`, `libraries/` (checksum · ข้ามถ้าไม่เปลี่ยน) → classloader ของ ShopArchive เอง (ใช้ต่อใน P13) → start core
- [ ] Root resolver: โฟลเดอร์ของ jar (ไม่ใช่ cwd) · `--root <dir>` · root เขียนไม่ได้ → error + exit
- [ ] Windows: root path มีอักขระนอก ASCII → warn (JDK-8195129) · `--root` มี `?` → error ชัดเจน (argv เสียอักขระ)
- [ ] Filesystem check: FAT32 / exFAT / network share → ไม่ start · root บน SD card (`/dev/mmcblk*`) → warn
- [ ] ล้าง `tmp/` ตอน start
- [ ] First run สร้าง layout ตาม `MASTER.md` · ไม่ overwrite ของเดิม
- [ ] `data/session.lock` → รันซ้อนไม่ได้
- [ ] `data/server-id` (UUID) สร้างครั้งเดียว
- [ ] Shutdown: `stop` / SIGTERM / Ctrl+C → เรียก shutdown hooks ตามลำดับ (writer, connections ลงทะเบียนใน phase ของมัน) → exit 0
- [ ] CLI: `--root`
- [ ] Startup log จบด้วย `Done (X.XXs)! For help, type "help"`
- [ ] `start.sh`, `start.bat` + templates systemd, WinSW:
  - `-Djava.io.tmpdir=<root>/tmp/jvm -XX:-UsePerfData -XX:ErrorFile=<root>/crash-reports/hs_err_pid%p.log` + memory flags
  - `start.bat`: `chcp 65001` + `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`
  - marker property บอกว่า start ผ่าน script
- [ ] Start โดยไม่มี marker → console warn ว่าอาจมีไฟล์ถูกเขียนนอก root
- [ ] Test/lint: ห้าม `createTempFile` ที่ไม่ระบุ directory

## Verify
- โฟลเดอร์เปล่า → start ผ่าน script → layout ครบ + `Done`
- Java 17 → error ชัดเจน
- รันตัวที่ 2 → ถูกปฏิเสธ · `stop` → exit 0
- Start ผ่าน script → ไม่มีไฟล์ถูกเขียนนอก root (Process Monitor บน Windows · `strace` บน WSL2/Linux)
- Root path มีช่องว่าง → ใช้ได้ทั้ง Windows และ Linux · อักษรลาว/ไทย → Linux ใช้ได้ · Windows start ได้ + warn
- Console ใน Windows Terminal แสดงลาว/ไทยถูก
- Copy โฟลเดอร์ Windows ↔ Linux (WSL2) → start ได้
- Raspberry Pi 64-bit → `Done` (ถ้าใช้ Pi)

**จบ phase → หยุดรอ audit**
