package xyz.felismp.shoparchive.app.client

/** Uppercase hex in groups of four, as the server console prints it. */
fun formatFingerprint(pin: String): String = pin.filterNot { it.isWhitespace() }.uppercase().chunked(4).joinToString(" ")

/** The pin to store for a fingerprint the user confirmed. */
fun fingerprintToPin(fingerprint: String): String = fingerprint.filterNot { it.isWhitespace() }.uppercase()
