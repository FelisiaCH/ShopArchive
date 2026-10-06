package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.RedeemResponse

/** What the enroll form holds. Fields the redeem flags do not ask for are ignored. */
class EnrollInput(
    val password: String = "",
    val passwordRepeat: String = "",
    val pin: String = "",
    val pinRepeat: String = "",
    val mode: DeviceMode = DeviceMode.PERSONAL,
    val label: String = "",
)

/** The first thing wrong with [input] for the account [r] describes, or null. The server judges strength; this only catches typos. */
internal fun validateEnroll(r: RedeemResponse, input: EnrollInput): Problem? {
    if (r.passwordRequired) {
        if (input.password.isEmpty()) return Problem.PasswordEmpty
        if (!r.hasPassword && input.password != input.passwordRepeat) return Problem.PasswordsDiffer
    }
    if (input.pin.length != r.pinLength || !input.pin.all { it in '0'..'9' }) return Problem.PinFormat(r.pinLength)
    if (!r.hasPin && input.pin != input.pinRepeat) return Problem.PinsDiffer
    if (input.label.isBlank()) return Problem.LabelEmpty
    return null
}

internal fun enrollRequest(
    r: RedeemResponse, input: EnrollInput, platform: String,
    /** Set when adding a user to a device this app already holds: the server then adds the user to that device. */
    deviceId: String? = null, deviceCredential: String? = null,
): EnrollRequest = EnrollRequest(
    deviceLabel = input.label.trim(),
    platform = platform,
    mode = input.mode,
    password = input.password.takeIf { r.passwordRequired && r.hasPassword },
    newPassword = input.password.takeIf { r.passwordRequired && !r.hasPassword },
    pin = input.pin.takeIf { r.hasPin },
    newPin = input.pin.takeIf { !r.hasPin },
    deviceId = deviceId,
    deviceCredential = deviceCredential,
)
