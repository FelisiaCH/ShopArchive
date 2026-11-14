package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import xyz.felismp.shoparchive.app.flow.AppFlow

/** Settings: the language and what Record starts with, on this device; then Lock now or Back to the shop. */
@Composable
fun SettingsScreen(t: Translator, flow: AppFlow) {
    val language by flow.language.collectAsState()
    val recordStart by flow.recordStart.collectAsState()
    Screen(t.t("nav_settings")) {
        H2 { Text(t.t("language_label")) }
        Div(attrs = { classes("row") }) {
            languageOptions.forEach { option ->
                Btn(t.t(option.label), primary = language == option.tag, pressed = language == option.tag) { flow.setLanguage(option.tag) }
            }
        }
        H2 { Text(t.t("record_start_label")) }
        Div(attrs = { classes("row") }) {
            recordStartOptions.forEach { option ->
                Btn(t.t(option.label), primary = recordStart == option.start, pressed = recordStart == option.start) { flow.setRecordStart(option.start) }
            }
        }
        Div(attrs = { classes("row") }) {
            Btn(t.t("lock_now")) { flow.lockNow() }
            Btn(t.t("back"), primary = false) { flow.closeSettings() }
        }
    }
}
