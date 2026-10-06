# Plugin template

A working minimal plugin: config with defaults, a permission node, a console command, an HTTP route and `onConfigReload`.

## Make your own

1. Copy this folder to `plugins/<your-plugin>/` and add `include(":plugins:<your-plugin>")` to `settings.gradle.kts`.
2. Change `name`, `version` and `main` in `src/main/resources/plugin.yml`, the package and class under `src/main/kotlin`, and `archiveFileName` in `build.gradle.kts`.
3. Keep `compileOnly(project(":shoparchive-api"))` and the Kotlin plugin from `libs.versions.toml`. The server provides the api and the Kotlin runtime; a jar that contains `kotlin/` or `kotlinx/`, or was built with a newer Kotlin than the server, is not loaded.

## Build and install

```
./gradlew :plugins:<your-plugin>:jar
```

1. Copy `plugins/<your-plugin>/build/libs/<jar>` into the server's `plugins/` folder.
2. Start the server. A new or changed jar is not loaded until you approve it (the console prints the command and the jar's SHA-256): `plugins approve <name>`
3. Restart the server. `plugins` lists it as `enabled`, `plugins info <name>` shows what it registered and its config (secrets as `***`).

To update, put the new jar in `plugins/update/` (it replaces the old one at the next start) and approve it again.

## Try it

- Console: `template Noy`
- Edit `plugins/Template/config.yml`, then `reload Template`
- HTTP, as a signed-in app user: `GET /api/v1/x/Template/greet?name=Noy` with the usual `Authorization: Bearer <access token>` and `X-ShopArchive-Protocol` headers

## A channel plugin (Telegram, Discord, mail...)

A channel replaces the `Notifier` service (see its KDoc) and sends what the server's outbox hands it; the outbox does the queue, the retries and the status.

- In `onEnable`, read your config. If something you need is missing or wrong (an empty bot token or webhook), log a warning with `context.logger.warn(...)` and **do not register** the notifier: the server then uses the next one (at worst the core's log-only notifier) and keeps working.
- Otherwise register it above the core's priority (0). The shipped Telegram plugin uses 10 and `plugins/example-discord` 20; the highest one is used and two plugins with the same number clash, so pick a number of your own: `context.services.register(Notifier::class.java, MyNotifier(), 30, context.name)`. `plugins/example-discord` is a complete channel to copy (config with a secret, templates per language, status mapping, tests). The plugin api cannot take a registration back: a `reload` can register a channel whose config has become complete, but one that became incomplete keeps answering "try again" until the server restarts. `deliver` answers `Sent`, `Retry(reason)` (tried again later), or `Failed(reason)` (never going to work). Keep secrets out of reasons: they are stored and shown to admins.
- Format the text from `notification.fields` (`{type} {category} {item} {amount} {currency} {branch} {user} {time}` and, for `day.closed`, the day's figures) and print `notification.code` in it, so a message that arrives twice can be recognised.
- To also react to events yourself (a second channel next to the outbox), subscribe with `context.services.get(ShopEvents::class.java)!!.subscribe("entry.created") { ... }`. The server ends your subscriptions when the plugin is disabled or fails. Keep the listener short.
