# Running a ShopArchive server

How to run the server after the first start (see `README.md`).

## First start and logging in

The first start makes the settings files, the certificate (`certs/`), the branch `main` and the admin `owner`. After the "Done" line it prints `Owner login: user 'owner'  PIN <digits>`; every start prints a new PIN until `owner` has logged in on a device. The two names are `setup.first-branch` and `setup.first-user` in `config/shoparchive.yml` (empty: make nothing). The zip keeps the executable bit of `start.sh`; if yours lost it, `chmod +x start.sh`.

The app opens on a list of servers: the ones it finds on the same network (mDNS), and "Add server" for `IP:port` or `domain:port`. On the first connect the app trusts the server's key; if the key changes later, the app warns. Login is a name and a PIN, once per device; a user without a PIN sets one at login. The app asks for the PIN again only to delete entries, to manage users and devices, and to open the Console.

## Console and commands

In the console, `help` lists the commands and `stop` stops the server (always stop this way or with Ctrl+C / SIGTERM, not by killing the process). The app has the same commands in its Console screen (Settings), for a user who holds the permission `shoparchive.command.<name>` of a command (an op holds them all); `user`, `perm`, `role`, `devices`, `op` and `deop` are for ops only. `status` shows the address, uptime and the last backup; `user list`, `devices <name>`, `category add ...` and `perm search <keyword>` manage the rest.

Staff: the owner adds them with `user add <name>` (in the app's Console or the server console); they type their name in the app and set their own PIN. `user reset <name>` clears a user's PIN and devices, so they set a new PIN at the next login.

## Memory, flags and settings

Memory is 1 GB (`SHOPARCHIVE_MEMORY=2G ./start.sh` to change;  `set SHOPARCHIVE_MEMORY=2G` first on Windows). Command-line flags after the script name override `server.properties` for that run only: `--port`, `--bind-address`, `--log-level`, `--no-plugins` (start without any plugin).

Settings live in `server.properties` (name, `port` default 25655, `bind-address`, `lan-discovery`, `log-level`; applied at restart) and `config/shoparchive.yml` (time zone, locale, backup, notifications, plugins ...). Every key is explained in the comment above it, and a value that is not allowed is replaced by the nearest allowed one with a warning in the console. `reload` reads the config files again.

## Run as a service

- Linux: `service/shoparchive.service` explains itself in its first lines (copy to `/etc/systemd/system/`, edit the paths and user, `systemctl enable --now shoparchive`). Create a user without a shell first (`sudo useradd -r -d /opt/shoparchive shoparchive`) and give it the folder (`chown -R`). A service has no console to read the PIN from: start it once by hand first, log in as `owner` with the PIN it prints (and run `plugins approve`), `stop` it, then start the service. Only one server can run on a folder at a time.
- Windows: `service/shoparchive-winsw.xml` explains itself in its first lines (WinSW).

## Firewall

The server needs two things open on the machine it runs on:

- **TCP `port`** from `server.properties` (default 25655): the HTTPS port the apps use.
- **UDP 5353**: mDNS, so devices on the same network find the server (only if `lan-discovery` is true). Without it, add the server in the app by `IP:port`.

Linux (ufw), from the local network only:

```
sudo ufw allow from 192.168.1.0/24 to any port 25655 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 5353 proto udp
```

(Use your own network instead of `192.168.1.0/24`. If apps outside your network must connect, allow the TCP port from anywhere: `sudo ufw allow 25655/tcp`.)

Windows (Defender Firewall), in an elevated Terminal:

```
netsh advfirewall firewall add rule name="ShopArchive" dir=in action=allow protocol=TCP localport=25655
netsh advfirewall firewall add rule name="ShopArchive mDNS" dir=in action=allow protocol=UDP localport=5353
```

When Windows asks "Allow Java on private networks?" on the first start, allowing it does the same.

## Going online (outside the shop's network)

Making the server reachable from the internet is the administrator's job, the way it is for a Minecraft server. ShopArchive does not do it for you and needs no outside service.

1. Find out whether you can: look at the WAN IP on your router's status page. `100.64.x.x` to `100.127.x.x` is CGNAT; `10.x`, `172.16-31.x` or `192.168.x` means another router (double NAT) sits in front. Anything else is a public address.
2. Public address: forward the one TCP port (`port`) on the router to this machine, and give the machine a fixed LAN address. Test from a phone on mobile data: "Add server" with `public-IP:port`.
3. CGNAT / double NAT: ask your ISP for a public (fixed) IP, or use a tunnel you choose and run yourself.
4. If clients reach the server by a domain name, add it to `network.domains` in `config/shoparchive.yml` so the certificate covers it (restart). The server's key stays the same, so devices that have logged in keep working.

Only the HTTPS port needs to be reachable; do not forward 5353.

## Plugins

A plugin has full access to the server, so a jar is not loaded until you approve it (`plugins.require-approval`, on by default). The release ships `plugins/shoparchive-telegram.jar`.

```
plugins                   list the plugins and their state
plugins info Telegram     what a plugin is and whether it is loaded (secrets are shown as ***)
plugins approve Telegram  approve the jar as it is on disk now (a changed or replaced jar needs approving again)
plugins revoke Telegram   take the approval away
```

Approving and revoking apply at the next start.

### Telegram

Sends "entry recorded" and "day closed" messages to Telegram chats.

1. In Telegram, talk to `@BotFather`, send `/newbot`, and keep the **token** it gives you. Make a new token for each server; if one leaks, `/revoke` it at BotFather.
2. Press Start in a private chat with the bot, or add the bot to the group or channel that should get the messages.
3. Find the chat id: a person is a number such as `123456789`; a group or channel is `-1001234567890`; a public channel can also be `@channelname`.
4. Approve the plugin and restart the server once (`plugins approve Telegram`, `stop`, start again): the first start copies `plugins/Telegram/config.yml`.
5. Edit `plugins/Telegram/config.yml`: `bot-token`, `chat-ids` (a list), and if you like `language` (lo, th, en), `events` and the message `templates`. Every key is explained in the file.
6. In the console: `reload Telegram`. The plugin warns at start if something is missing.

The bot token is a secret: it is in `plugins/Telegram/config.yml`, and therefore in every backup (see below). While a message cannot be sent the server keeps it in its outbox and tries again (`notify` lists the messages, `notify resend <id>` sends one again).

### Hotfixes

A hotfix is a jar in `plugins/` (its `plugin.yml` says `hotfix: true`) that replaces one method of the core until the next release fixes
it for good. You get one from whoever maintains your server. It is approved like a plugin (`plugins approve <name>`, then restart) and is
**always** checked, even with `plugins.require-approval: false`; it is applied at start, before anything else runs. `status` and
`plugins` show each hotfix and whether it is active.

- A `security` hotfix that no longer matches the core (made for another build) stops the server from starting, with a message that
  says why. A `bug` hotfix that does not match is skipped with a warning.
- Two jars that cannot be used together (same name, same classes) also stop the start; the message names them.
- `./start.sh --ignore-hotfix <ID>` (or `start.bat --ignore-hotfix <ID>`) starts without the hotfix for that ID and warns at every start.
- `--no-patches` starts with no hotfix at all, to find out whether a hotfix is the problem.
- When a release fixes it for good, `status` says the hotfix is fixed in the core: remove its jar from `plugins/`.

## Apps and updates

The apps (Android APK, Windows MSI) are not part of this zip. Each release gets them next to it.

- **Android**: copy the `.apk` to the phone and open it. Android asks to allow installing from this source (the browser or file manager you used); allow it for this install. The APK from the release is signed with the shop's key; builds without a key are not installable until signed.
- **Windows**: run the `.msi`. It is not signed, so SmartScreen shows "Windows protected your PC": choose **More info**, then **Run anyway**.
- **iOS**: later. There is no iPhone app in this release.

### Updating the apps from the server

Put the new app files in the server's `downloads/` folder (the server makes it). The apps check it after sign-in and offer "New version available"; the user taps Install, the app downloads the file from the server (checked by its SHA-256) and installs it.

The release builds already name their files this way (`ShopArchive-<version>-<build>.apk` and `.msi`): copy them into `downloads/` unchanged. File names decide everything: `ShopArchive-<version>.apk` or `ShopArchive-<version>.msi`, where `<version>` is `x.y.z`. If you hand out a second build of the same version, add the build number: `ShopArchive-1.0.1-2.apk`. Examples: `ShopArchive-1.0.0.apk`, `ShopArchive-1.2.0.msi`, `ShopArchive-1.2.0-2.msi`. Other files and other names are ignored. A file counts for an app when it is newer than the app (higher version, or the same version and a higher build number; a file with no build number counts as build 0).

On Windows the app closes and starts the installer; on Android the system asks the user to confirm. The folder is not part of a backup: keep the installers yourself.

## Updating the server

Stop the server, replace `shoparchive-server.jar` (and the start scripts) with the new release's, start again. The old jar's `versions/` folder is left alone. Take a `backup` first. If a hotfix is installed, read the release notes: when the new release includes its fix, the hotfix is skipped and can be removed; if not, a `security` hotfix made for the old build stops the new one from starting until you install the matching hotfix or start with `--ignore-hotfix <ID>`. A new app and an older server (or the other way round) work together as long as they speak the same protocol; if not, the app says which side to update.

## Backup and restore

### What is backed up

The console command `backup` (and the daily run, see below) writes `backups/<yyyyMMdd-HHmmss>.zip`:
everything in the server folder except

- the slip images (`record/**/slip-N.jpg|png`): each is copied once, under the checksum of its content, to `backups/slips/<sha256>.<jpg|png>`. The zip's `backup-manifest.json` says which slip belongs where.
- `cache/`, `logs/`, `tmp/`, `libraries/`, `versions/` (rebuilt or runtime), `backups/`, `exports/`, `downloads/`.
- the release itself: `shoparchive-server.jar`, `start.sh`, `start.bat` (you get these from the release zip) and `data/session.lock`.

The zip does include `certs/` (so the apps keep trusting the server's key), `user/`, `record/` (entries, history, sessions), `data/`, `config/`, `plugins/` and `crash-reports/`.
`backups/slips/` is never pruned: it only grows by the slips that are new.

**Keep the backups private.** A backup zip, and every copy of it (`copy-to`), contains the server's private key and its password (`certs/keystore.p12` and `certs/keystore.pass`), the password and PIN hashes of every user (`user/*.yml`), the hashed device credentials (`data/devices/`), the plugin settings including secrets such as bot tokens (`plugins/<Name>/config.yml`), and all the records. Anyone who holds a zip can pose as the server to your devices and can try to guess PINs offline. Keep zips on protected storage, and treat a lost or stolen USB drive as a compromise of the server's key:

1. Stop the server and move `certs/keystore.p12` and `certs/keystore.pass` out of `certs/` (keep them until you are done).
2. Start the server: it makes a new key and certificate. Devices trust the server by its key, so every app warns that the key changed. Tell your users to expect it, and since the hashes were exposed, `user reset <name>` for each user so they set a new PIN at their next login.
3. Change the secrets in the plugin settings (for example a Telegram bot token) at the service that issued them.
4. Delete the lost zips from the disks you control, and use a new `copy-to` drive.

Keep a copy somewhere else than the server's disk: set `backup.copy-to` in `config/shoparchive.yml` to a folder on a USB drive or another disk. The folder must exist; the server puts everything in a folder of its own inside it, `ShopArchive-<server-id>/` (zips, and the slip images in `slips/`), and only ever deletes old zips there, and only zips whose manifest carries this server's id. If the folder is missing (drive unplugged) the server logs a warning and tries again at the next backup.

Settings (`config/shoparchive.yml`):

```yaml
backup:
  enabled: true     # a daily backup while the server runs
  time: 03:00       # server time zone; also: a start with no backup in 24 hours makes one a minute later
  keep: 14          # zips kept in backups/ and in copy-to (only this server's own)
  pause-timeout-seconds: 30  # how long a backup waits for running changes (users, devices) before it gives up
  copy-to: ""       # e.g. "E:\\ShopArchiveBackups" or "/mnt/usb/shoparchive"
```

`status` shows the last backup (time, ok or the reason it failed, size) and the result of the copy.

### Restore

1. Stop the server (`stop`).
2. Make a new folder and unpack the release zip into it (this brings the jar and the start scripts).
3. Unzip the backup zip into that folder, over the release files (`unzip 20261002-030000.zip -d new-folder` or any zip tool). This brings `config/`, `user/`, `record/`, `data/`, `certs/`, `plugins/` and `backup-manifest.json`.
4. Start the server in the new folder (`start.sh` / `start.bat`). Do not let devices use it yet: no entries or slips before the next step.
5. Put the slip images back right away. (Why now: a new slip gets the next free number in its entry's folder, so a slip saved before this step could take the name of one that is still to come back; the command never overwrites a file that is there, and reports it as a conflict instead.) In the server console, give the folder that holds the slip images: the old server's `backups/slips`, or `ShopArchive-<server-id>/slips` inside your `copy-to` folder:

   ```
   backup restore-slips /path/to/old-server/backups/slips
   ```

   It checks every image against its checksum, never overwrites a different file, and lists anything missing. Run it again after fixing a problem; images that are in place are skipped.
6. Check `status` and the dashboard; keep the old folder until you are sure.

A restore is only as good as its last test: try steps 1-6 on a spare folder once before you rely on it.
