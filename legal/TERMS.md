> **DRAFT. This is not legal advice.** This document is a template. It has not been reviewed by a lawyer and is not written for the law of any particular country. Have a qualified professional review it and adapt it before you rely on it.

# ShopArchive Terms of Use

These terms cover the ShopArchive software: the server, the apps, and the plugins in this repository. They also serve as the end-user license terms for the apps.

## 1. Who is who

- **The developers** write and publish the ShopArchive source code and releases.
- **The operator** is the shop or person who installs and runs a ShopArchive server, and who adds users to it.
- **Users** are the people who sign in to an operator's server with the apps, for example the shop owner and staff.

The developers do not run any ShopArchive server, do not host shop data, and are not a party to any arrangement between an operator and its users.

## 2. License

ShopArchive is open-source software licensed under the Apache License, Version 2.0 ([LICENSE](../LICENSE)). Your rights to use, copy, modify and distribute the software come from that license. Nothing in these terms limits those rights. If these terms and the license conflict, the license applies.

## 3. No warranty, limitation of liability

The software is provided "AS IS", without warranties or conditions of any kind. The developers' liability is limited as set out in sections 7 and 8 of the Apache License 2.0. These terms do not repeat or change those sections.

ShopArchive is a record-keeping tool. It is not accounting, tax or legal advice. It is not a payment system, and it does not move money. The operator remains responsible for checking the figures it shows.

## 4. The operator's responsibilities

By running a server, the operator is responsible for:

- the computer, network and storage the server runs on, and for making the server reachable from outside the shop's network if they choose to do so;
- the user accounts they create, the permissions they grant, and the data that is recorded on their server;
- keeping backups, if they want them. Backup is off by default;
- keeping secrets private: the server's private key in `certs/`, user PIN hashes, and plugin settings such as a Telegram bot token;
- which plugins and hotfixes they approve. A plugin has full access to the server;
- the outside services a plugin connects to, if the operator turns that plugin on (for example Telegram, or BCEL's slip check), and those services' own terms;
- telling users how their data is handled, and following the law that applies to the operator. [legal/PRIVACY.md](PRIVACY.md) is a starting point.

## 5. Users

Users sign in to a server that an operator runs. The operator decides which accounts exist and what each account may see and do. Questions about the data on a server, or requests to change or remove it, go to that server's operator. The developers cannot see or change it.

## 6. Third-party services and components

The Telegram and BCEL plugins connect to services that the developers do not control. The BCEL check uses an endpoint that BCEL does not publish as an API for shops, so it may change or stop working at any time.

The apps and the server include third-party libraries under their own licenses. Platform stores and installers (for example Android, Windows, or AltStore for iPhone) have their own terms.

## 7. Modified versions

Anyone may distribute modified versions under the Apache License 2.0. The developers are not responsible for builds, plugins or hotfixes that they did not publish.

## 8. Changes

These terms may change with new releases. The version in the release that you use applies to that release.
