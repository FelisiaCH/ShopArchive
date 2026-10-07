# ShopArchive server

A ShopArchive server is this folder: one jar, two start scripts, and the data the server makes next to them (`config/`, `user/`, `record/`, `certs/` ...). Nothing is stored anywhere else and nothing needs an outside service.

Contents of the release zip:

| File | What it is |
| --- | --- |
| `shoparchive-server.jar` | the server |
| `start.sh` / `start.bat` | start it on Linux / Windows |
| `service/shoparchive.service` | systemd unit template |
| `service/shoparchive-winsw.xml` | WinSW (Windows service) template |
| `plugins/shoparchive-telegram.jar` | the Telegram notification plugin (off until you approve and set it up) |
| `README.md` | this file |
| `OPS.md` | running the server: console, service, firewall, going online, plugins, updates, backup |

## Requirements

- Java 21 (`java -version`). Set `JAVA_HOME` if it is not the `java` on your PATH.
- A machine that stays on: a PC, a mini PC, or a Raspberry Pi 4/5 (64-bit OS, root on an SSD, not an SD card). Put the machine, the router and the modem on a UPS.
- Windows: put the server folder in Defender's exclusion list (Windows Security > Virus & threat protection > Manage settings > Exclusions) and run it in Windows Terminal (the console prints Lao and Thai).

## Start

1. Unzip into a folder of its own (a local disk, not a network path) and run `./start.sh` on Linux / macOS or `start.bat` on Windows. After the "Done" line the console prints `Owner login: user 'owner'  PIN <digits>`.
2. Install the app, pick this server in the list (or "Add server" with `IP:port`), and log in as `owner` with the PIN printed in the console.
3. Other branches, or outside the shop's network: give the machine a fixed LAN address and forward the TCP port (default 25655) on the router. In the app at the other branch, "Add server" with `domain:port` (or `public-IP:port`). Details: `OPS.md`.

- Staff: `user add <name>` in the app's Console or the server console; they type their name in the app and set their own PIN.
- `stop` stops the server.
- Everything else: `OPS.md`.
