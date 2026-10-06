# Hotfix template

A hotfix replaces the body of one core method with a method of your jar, without touching the core's jar. It exists for the case
"a bug or a security hole is in a released core and the real fix has to wait for the next release". Read this before writing one;
a plugin (`plugins/template`) is the right tool for everything else.

## How it works

- The launcher reads `plugins/*.jar` with `hotfix: true` **before** the core is loaded, and decides for each one. A hotfix jar must be
  approved like a plugin (`plugins approve <name>`, which stores the jar's SHA-256 in `plugins/approved.yml`); it is **always** checked,
  even with `plugins.require-approval: false`. Put the jar straight in `plugins/` (a jar in `plugins/update/` is moved by the core after
  the launcher has looked, so it applies one restart later).
- It patches the bytecode of the core's classes when they are loaded (ASM, no Java agent). Your classes live in the core's own classloader.
- Only a method the core marked `@HotfixTarget` can be replaced. The mark promises that its JVM name and signature stay stable: it is
  not `inline`, not `suspend`, and has no value-class parameter (those mangle the name). The launcher refuses the others.
- The patch is pinned to the SHA-256 of the target class file and to the core build in `target-build`. A different class or build means
  the patch is not applied.

## Write one

1. Copy this folder to `plugins/<your-hotfix>/` and add `include(":plugins:<your-hotfix>")` to `settings.gradle.kts`.
   **Rename the package** `xyz.felismp.shoparchive.plugins.hotfix` to one that is unique to this hotfix (for example
   `xyz.felismp.shoparchive.hotfix.sa1`), also under `src/main/kotlin/`. All hotfix jars and the core share one classloader, so the
   first jar with a class name wins; a class that exists in two hotfix jars, or in the core or launcher, is refused at start and the
   server does not start (`--ignore-hotfix <ID>` starts it without that fix). The template's own `PatchesKt` would collide if two
   copies kept the package.
2. `plugin.yml`: `name`, `fixes` (the IDs), `severity` (`security` or `bug`) and `target-build` (the core build it is for: `status` shows
   it, it is `buildNumber` in `gradle.properties`). No `main`, no `api-version`.
3. Write a `public static` method with the same parameters and return type as the target (an instance method gets the object as first
   parameter) and put `@HotfixPatch(targetClass, method, descriptor, classSha256)` on it. In Kotlin: a top-level function. See `Patches.kt`.
   Get `descriptor` from `javap -p -cp <core jar> <class>`, and `classSha256` by typing `plugins hotfix-hash <class>` in the console of
   a server running that exact build.
4. `./gradlew :plugins:<your-hotfix>:jar`, copy the jar to the server's `plugins/`, start once, `plugins approve <name>`, restart.
   `plugins` lists it under `Hotfixes` as `active`; `status` counts it.

Keep it small: it replaces the whole body (the old one cannot be called), and it must not need classes that only a plugin loads.
Two hotfixes for the same method refuse to start together.

## What happens at start

| Situation | Result |
|---|---|
| every ID in `fixes` is in the core's `fixed-issues` | skipped, logged: the core already fixes it, remove the jar |
| not approved (new or changed jar) | not applied, logged with the command to approve |
| `security`, other build or other class bytes | the server **does not start**; `--ignore-hotfix <ID>` starts it anyway and warns at every start |
| `bug`, other build or other class bytes | warning, not applied, the server starts |
| the patch cannot be made (matches no method, target not `@HotfixTarget`, wrong signature, `suspend`/mangled) | the server does not start (same `--ignore-hotfix <ID>`) |
| a class name that two hotfix jars (or a hotfix jar and the core or launcher) both contain | the server does not start (same `--ignore-hotfix <ID>`); the message names the jars and the class |
| a multi-release jar (`Multi-Release: true`, or classes under `META-INF/versions/`) | the server does not start (same `--ignore-hotfix <ID>`): multi-release hotfix jars are not supported |
| `--no-patches` | no hotfix is applied (use it to find out whether a hotfix is the problem) |

## Fix workflow

hotfix jar -> audit -> deploy -> merge the real fix into the core and add the ID to `server/fixed-issues.txt` (and a row to `docs/fixes.md`)
-> release -> servers that update skip the hotfix and `plugins` tells the admin to remove it.

This template's own patch (`formatUptime`) only shows the mechanism and uses a placeholder hash, so it is reported as "not applied" until you
paste a real hash.
