package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.events.*
import org.jetbrains.compose.web.dom.*

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
fun Btn(label: String, enabled: Boolean = true, primary: Boolean = true, submit: Boolean = false, action: () -> Unit = {}) {
    Button(attrs = {
        classes("btn")
        if (!primary) classes("secondary")
        type(if (submit) ButtonType.Submit else ButtonType.Button)
        if (!enabled) disabled()
        onClick { action() }
    }) { Text(label) }
}

/** What a text box holds: a PIN is digits kept secret, a password is secret, an amount opens the number keyboard. */
enum class FieldKind { Text, Pin, Password, Amount }

/** A text box with its label above. */
@Composable
fun Field(label: String, text: String, onChange: (String) -> Unit, kind: FieldKind = FieldKind.Text, enabled: Boolean = true) {
    val type: InputType<String> = if (kind == FieldKind.Pin || kind == FieldKind.Password) InputType.Password else InputType.Text
    Label(attrs = { classes("field") }) {
        Span { Text(label) }
        Input(type = type, attrs = {
            value(text)
            onInput { onChange(it.value) }
            if (!enabled) disabled()
            when (kind) {
                FieldKind.Pin -> { attr("inputmode", "numeric"); attr("autocomplete", "off") }
                FieldKind.Password -> attr("autocomplete", "off")
                FieldKind.Amount -> { attr("inputmode", "decimal"); attr("autocomplete", "off") }
                FieldKind.Text -> attr("autocomplete", "username")
            }
        })
    }
}

/** A form, so Enter in a field does what the submit button does. */
@Composable
fun SubmitForm(action: () -> Unit, content: @Composable () -> Unit) {
    Form(attrs = { onSubmit { it.preventDefault(); action() } }) { content() }
}
