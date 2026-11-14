package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.events.*
import org.jetbrains.compose.web.dom.*
import org.khronos.webgl.Int8Array
import org.w3c.files.Blob

// The few building blocks the screens share. Every look is a class in styles.css (the server's CSP allows no inline style).

@Composable
fun Screen(title: String, content: @Composable () -> Unit) {
    Main(attrs = { classes("screen") }) {
        if (title.isNotEmpty()) H1 { Text(title) }
        content()
    }
}

@Composable
fun Banner(text: String, error: Boolean = false) {
    Div(attrs = { classes("banner"); if (error) classes("error"); attr("role", if (error) "alert" else "status") }) { Text(text) }
}

@Composable
fun Note(text: String) {
    P(attrs = { classes("muted") }) { Text(text) }
}

@Composable
fun Btn(label: String, enabled: Boolean = true, primary: Boolean = true, submit: Boolean = false, current: Boolean = false, pressed: Boolean? = null, action: () -> Unit = {}) {
    Button(attrs = {
        classes("btn")
        if (!primary) classes("secondary")
        type(if (submit) ButtonType.Submit else ButtonType.Button)
        if (!enabled) disabled()
        if (current) attr("aria-current", "page")
        pressed?.let { attr("aria-pressed", it.toString()) }
        onClick { action() }
    }) { Text(label) }
}

/** An image from [bytes], held as a blob URL while it shows; [key] keeps one URL per item, not one per recomposition. */
@Composable
fun BlobImage(key: Any, bytes: ByteArray, alt: String) {
    val url = remember(key) {
        window.asDynamic().URL.createObjectURL(Blob(arrayOf<dynamic>(bytes.unsafeCast<Int8Array>()))).unsafeCast<String>()
    }
    DisposableEffect(key) { onDispose { window.asDynamic().URL.revokeObjectURL(url) } }
    Img(src = url, alt = alt, attrs = { classes("slip-image") })
}

/** What a text box holds: a PIN is digits kept secret, a password is secret, an amount opens the number keyboard. */
enum class FieldKind {
    Text,

    /** Plain text that the password manager must not offer to fill. */
    Plain,
    Pin,
    Password,
    Amount,

    /** Several lines (a note). */
    Multiline,
}

/** A text box with its label above. */
@Composable
fun Field(label: String, text: String, onChange: (String) -> Unit, kind: FieldKind = FieldKind.Text, enabled: Boolean = true) {
    val type: InputType<String> = if (kind == FieldKind.Pin || kind == FieldKind.Password) InputType.Password else InputType.Text
    Label(attrs = { classes("field") }) {
        Span { Text(label) }
        if (kind == FieldKind.Multiline) {
            TextArea(value = text, attrs = {
                onInput { onChange(it.value) }
                if (!enabled) disabled()
                attr("autocomplete", "off")
            })
        } else {
            Input(type = type, attrs = {
                value(text)
                onInput { onChange(it.value) }
                if (!enabled) disabled()
                when (kind) {
                    FieldKind.Plain -> attr("autocomplete", "off")
                    FieldKind.Pin -> { attr("inputmode", "numeric"); attr("autocomplete", "off") }
                    FieldKind.Password -> attr("autocomplete", "off")
                    FieldKind.Amount -> { attr("inputmode", "decimal"); attr("autocomplete", "off") }
                    FieldKind.Text -> attr("autocomplete", "username")
                    FieldKind.Multiline -> Unit
                }
            })
        }
    }
}

/** A form, so Enter in a field does what the submit button does. */
@Composable
fun SubmitForm(action: () -> Unit, content: @Composable () -> Unit) {
    Form(attrs = { onSubmit { it.preventDefault(); action() } }) { content() }
}
