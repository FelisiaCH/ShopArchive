# Decisions

Stable เท่านั้น · version จริงอยู่ที่ `gradle/libs.versions.toml` · เพิ่ม library ใหม่ → เพิ่มแถวที่นี่

## Toolchain
| Tool | Version | เหตุผล |
|---|---|---|
| Kotlin | 2.4.20 | stable ล่าสุด (2.5.0 ยังเป็น Beta) · Compose compiler plugin ใช้ version เดียวกัน |
| Gradle (wrapper) | 9.8.0 | stable ล่าสุด · ใช้กับ AGP 9.4.1 + KGP 2.4.20 ได้ · pin `distributionSha256Sum` |
| AGP | 9.4.1 | stable ล่าสุด · ต้องใช้กับ compileSdk 37 |
| Compose Multiplatform | 1.12.1 | stable ล่าสุด (1.13 ยัง alpha) · ไม่ใช้ Material3 (stable คือ 1.9.0 · ที่มากับ 1.12 เป็น alpha) → เลือก design system ตอน P01 |
| Shadow (`com.gradleup.shadow`) | 9.6.1 | fat jar ของ `server-launcher` · ยังไม่ apply (ใช้ใน P02) |
| JDK | 21 | Temurin บน CI · เครื่อง dev ใช้ Microsoft build 21.0.12 (ติดตั้งไว้แล้ว · เป็น OpenJDK เหมือนกัน) |
| XcodeGen | ล่าสุดจาก brew (2.46.0 ตอนเขียน) | ไม่มี Mac → เขียน `.pbxproj` มือแล้วตรวจไม่ได้ · เขียน `iosApp/project.yml` แล้ว generate บน CI แทน · ไม่ pin (brew ไม่รองรับ) |
| GitHub Actions | checkout v7 · setup-java v6 · gradle/actions v6 · upload-artifact v7 | major ล่าสุดที่ stable |

## Libraries
| Library | Version | เหตุผล |
|---|---|---|
| Ktor (server-core, server-netty, client-core) | 3.6.0 | Server = Netty ตาม MASTER · client engine (OkHttp / Darwin / desktop) เลือกตอน P08 |
| kaml | 0.104.0 | YAML 1.2 ทั้ง config และ data |
| BouncyCastle (bcprov, bcpkix `-jdk18on`) | 1.86 | cert ECDSA P-256 + Argon2id |
| Log4j (api, core) | 2.26.1 | ล่าสุดของสาย 2.x (3.0.0 ยัง beta) |
| TerminalConsoleAppender | 1.3.0 | console + JLine แบบ Paper |
| Koin (core) | 4.2.2 | DI ของ client · ตาม MASTER |
| kotlinx-serialization (json + plugin) | 1.11.0 | ล่าสุดที่ stable (1.12.0 ยัง RC) |
| kotlinx-coroutines (core, swing) | 1.11.0 | `swing` จำเป็นให้ Compose Desktop มี Main dispatcher · ใช้อยู่แล้วใน `composeApp` |
| kotlinx-datetime | 0.8.0 | ไม่ใช้สาย `-0.6.x-compat` |
| navigation-compose (`org.jetbrains.androidx`) | 2.9.2 | stable ล่าสุด (CMP 1.12.1 มากับ 2.10.0-beta01 · beta จึงไม่ใช้) |
| lifecycle-viewmodel-compose (`org.jetbrains.androidx`) | 2.11.0 | ตรงกับที่ CMP 1.12.1 ใช้ |
| activity-compose | 1.13.0 | ล่าสุดที่ stable · ใช้ใน `androidApp` |

ตรวจแล้ว (probe ชั่วคราว · ไม่ commit): navigation 2.9.2 + lifecycle 2.11.0 + CMP 1.12.1 รันบน Desktop ได้ (NavHost + `viewModel`) · ชุด server ทั้งหมด compile + รันได้บน JDK 21 · ใน catalog แต่ยังไม่มี module ไหนใช้: ktor, koin, kaml, bouncycastle, log4j, terminalconsoleappender, serialization, datetime, navigation, lifecycle

## Spike (`spike/` · ลบพร้อม spike)
| Library | Version | เหตุผล |
|---|---|---|
| JmDNS | 3.6.3 | mDNS `_shoparchive._tcp` สำหรับ T2 · ตัวเดียวกับที่ P05 ระบุ |
| JLine (reader, terminal, terminal-jna) | 3.30.17 | TerminalConsoleAppender 1.3.0 ใช้สาย 3.x · `terminal-jna` ทดสอบ native load (T3) |
| JNA | 5.19.1 (resolved) | มากับ `jline-terminal-jna` |

## Deviations จาก P00 / MASTER
| เรื่อง | ที่ทำ | เหตุผล |
|---|---|---|
| module `androidApp` เพิ่ม | `androidApp` = Android application + `MainActivity` · `composeApp` และ `shared` ใช้ `com.android.kotlin.multiplatform.library` | AGP 9 ไม่ให้ `com.android.application` อยู่ module เดียวกับ `org.jetbrains.kotlin.multiplatform` (error: "not compatible ... since AGP 9.0") · เป็นเลย์เอาต์ที่ Google/JetBrains แนะนำ |
| `iosApp` | `project.yml` (XcodeGen) แทน `.xcodeproj` | ดูตาราง toolchain · `iosApp/*.xcodeproj` อยู่ใน `.gitignore` |
| Android platform บน CI | `platforms;android-37.0` | id ใน sdkmanager คือ `37.0` ไม่ใช่ `37` |
| App name ในทุก locale | `ShopArchive` เหมือนกันทั้ง en / lo / th | ชื่อแบรนด์ · แก้ได้เมื่อมีชื่อภาษาลาว/ไทย |
