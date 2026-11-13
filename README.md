# ShopArchive

ShopArchive is a self-hosted cash book for small shops. A shop runs the server on its own computer and staff record the day's money from the apps. All data stays in the server's folder. The software does not depend on any outside service.

The server is a single Java 21 jar (`shoparchive-server.jar`). It runs from a folder, the way a Minecraft (Paper) server does: start scripts, YAML config files and a console. The apps are for Android and for desktop (a Windows installer is provided; the desktop app also runs from source on Linux). An iOS app is kept in a separate repository ([ShopArchiveiOS](https://github.com/FelisiaCH/ShopArchiveiOS)).

## What it does

- Records income and expenses per branch: category, item, note, and the amount for each currency and payment method (cash or transfer).
- Opening and closing of the day: starting change, counted cash, expected cash, the difference and the handover. A day can be opened again for another round.
- Slip photos attached to entries. The app reads the amount and QR code from a slip on the device, and warns when the same slip has been recorded before.
- Day notes from staff to the owner.
- Dashboard, history (entries keep their edit history and are never hard-deleted), reports, and CSV or XLSX export.
- Users with name and PIN login, roles and per-command permissions. Each device is remembered after the first login.
- Apps find the server on the local network (mDNS) or connect by `IP:port` or domain. The connection uses HTTPS with the server's own certificate, which each app trusts on first use.
- A console on the server and the same commands in the app for users who have the permission.
- Plugins and hotfixes, loaded only after the administrator approves them. The release includes two plugins, both off by default:
  - Telegram: sends messages about entries, closed days, notes and new devices to Telegram chats.
  - BCEL: checks BCEL OneProof slips with the bank.
- App updates are delivered from the shop's own server.
- An optional daily backup, off by default.

## Running a server

See [server-launcher/scripts/OPS.md](server-launcher/scripts/OPS.md).

## Legal

- [Terms of Use](legal/TERMS.md) (draft)
- [Privacy Policy](legal/PRIVACY.md) (draft)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
