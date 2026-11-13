> **DRAFT. This is not legal advice.** This document is a template. It has not been reviewed by a lawyer and is not written for the law of any particular country. Have a qualified professional review it and adapt it before you rely on it. Replace every `[bracketed]` part with your own details.

# ShopArchive Privacy Policy (template for operators)

ShopArchive is open-source software that a shop runs on its own computer. This policy has two parts:

- **Part A** is about the developers. It applies to everyone.
- **Part B** is a template for the shop that runs a server (the **operator**). The operator should adapt it and give it to its users.

---

## Part A: The developers

The developers of ShopArchive do not collect, receive or store data from any server or app.

- The ShopArchive code in the server and the apps has no telemetry, analytics, crash reporting or "phone home" to the developers. The Android app includes Google ML Kit for reading slips. Google's documentation says the library may send usage and diagnostic data to Google. [Verify this for the build you ship.]
- Neither the server nor the apps contact the developers or GitHub on their own. Crash reports are files written in the server's folder (`crash-reports/`) and are not sent anywhere.
- App updates come from the operator's own server (its `downloads/` folder), not from the developers.
- The server connects to an outside service only when the operator turns on a plugin that does so. See B6.

If you download ShopArchive from GitHub or install the iPhone app through AltStore, those services handle your data under their own policies.

---

## Part B: Template for the operator

**Controller:** [shop name, address, contact]. We run our own ShopArchive server at [location]. We decide what is recorded and who may see it. The ShopArchive developers have no access to this data.

### B1. What is stored on our server

All data is kept as files in the server's folder on our computer.

| Data | Details | Where |
|---|---|---|
| User accounts | Name, display name, role, branches, permissions, language. The PIN (and password, if one is set) is stored only as an **Argon2id** hash. The one-time setup code is also hashed. The account also stores the count of wrong PINs and the lock time. | `user/<name>.yml` |
| Devices | A random device id, a label and the platform (for example Android or Windows). For each user on the device: a SHA-256 hash of the device credential, when it was created and last used, and the number of wrong PINs. | `data/devices/` |
| Entries | Date, branch, income or expense, category, item, note, amounts per currency and method (cash or transfer), who created the entry and when, and the edit history (who, when, what changed). A deleted entry is marked as deleted and is not erased. | `record/` |
| Slip photos | Images of payment slips attached to entries, with their SHA-256 checksum and the text of the slip's QR code. | `record/` |
| Bank confirmations | Only if the BCEL plugin is on: what BCEL returned for a slip (amount, time, payee name and account, and the payer's name and account as BCEL masks them). | stored with the entry |
| Day sessions | Opening change, counted and expected cash, difference, handover, note, who closed the day and when. | `record/` |
| Day notes | Notes from staff to the owner, with author and time. Notes are not edited or deleted once written. | `record/notes/` |
| Security log | One line for each security event: time, event, user, device, **IP address** and result. Secrets, codes, tokens and hashes are never written to this log. | `data/audit/` |
| Server log | Operational messages. Rolled over daily and at each start. | `logs/` |
| Outbox | Messages waiting to be sent to a plugin channel, such as Telegram. | `data/outbox/` |
| Backups | **Off by default.** When we turn it on, a zip of the server folder is written, and slip images are copied separately. | `backups/`, optional `copy-to` folder |

### B2. What the apps keep on a device

- The list of servers, settings, and the device's sign-in credential. On Android the credential is encrypted with a key in the Android Keystore. On Windows it is encrypted with Windows DPAPI for that Windows user.
- Entries that have not reached the server yet (the outbox), until they are sent.
- On Windows, uninstalling the app deletes this data (`%APPDATA%\ShopArchive` and `%LOCALAPPDATA%\ShopArchive`).

### B3. Device permissions

- **Android:** network access and local-network access (to reach our server), and permission to install updates that our server offers. The app does not ask for camera or photo permissions. It uses the system camera and the system photo picker, so it only receives a photo that you take or choose.
- **Windows:** the app uses the standard file dialog to pick slip images and to save exports.
- **iPhone (separate app):** local network, camera and photos, each requested by the system with an explanation.

### B4. Reading slips

The Android and Windows apps read the amount and the QR code from a slip photo **on the device**. Android uses Google ML Kit. Windows uses the OCR built into Windows. The photo is not sent anywhere else for this. Before upload, the app compresses the photo and sends it only to our server.

### B5. Connection security

The apps connect to our server over HTTPS (TLS). The server creates its own key and self-signed certificate. Each app trusts that key on the first connection and warns if the key ever changes. Our server is reachable at [local network only / the internet at address].

### B6. What leaves our server

Nothing leaves our server unless we turn on one of these plugins:

- **Telegram** [on/off]: messages are sent through Telegram's Bot API to the chats we choose, using a bot token that we create. Depending on the events we enable, the messages contain entry details (type, category, item, amounts, note, branch, user name, time), day-closing figures, staff notes, and new-device sign-ins (user and device). Slip photos can be attached to a message. Once a message is delivered, Telegram handles it under Telegram's own policy.
- **BCEL slip check** [on/off; off by default]: for a BCEL OneProof slip, the server sends the two reference numbers from the slip's QR code to `bcel.la`. No user or shop data is sent. Our server's IP address is visible to BCEL.

### B7. Why we keep this data

To keep the shop's financial records, to check cash and transfers, to control who can see and change records, and to protect accounts (for example, by limiting wrong PIN attempts and keeping a security log). [Add your legal basis, if your law requires one.]

### B8. How long we keep it

The software deletes nothing automatically except old backup zips (we choose how many to keep) and the Telegram plugin's delivery record (7 days by default). Records, notes, the security log and the server logs are kept until we remove them. [State your retention period and how you will apply it.]

### B9. Your rights

These are general principles, not a legal citation:

- **Minimal data.** We record only what the shop needs.
- **Access and copies.** Ask [contact]. We can show your records or export them as CSV or XLSX.
- **Correction.** Entries can be edited. Each change is kept in the entry's history.
- **Removal.** We can disable your account, remove your devices, or reset your PIN. Because financial records are kept, entries are marked as deleted rather than erased. If something has to be erased completely, we have to remove it from the files by hand, including any backups.
- **Questions or complaints.** [contact]. [Supervisory authority, if any.]

### B10. Changes

[Date of this version]. We will tell users when this policy changes.
