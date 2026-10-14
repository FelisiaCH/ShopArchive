package xyz.felismp.shoparchive.app.client

/*
 * A certificate made like the server's (self-signed, ECDSA P-256, SHA256withECDSA, names a LAN address), made once with the JDK's keytool;
 * its private key was a throwaway and is not kept. The three values below were computed on the JVM from it: they are what the server would
 * print and what an Android or Windows device pins. The iOS test must get the same hash out of Apple's Security framework.
 */

/** The certificate, DER, base64. */
internal const val VECTOR_CERT_DER_BASE64 =
    "MIIBdzCCARygAwIBAgIIaQI9rVDHHRUwCgYIKoZIzj0EAwIwFjEUMBIGA1UEAxMLU2hvcEFyY2hpdmUwIBcNMjYxMDE0MDkzNTE2WhgPMjEyNjA5MjAwOTM1MTZaMBYxFDASBgNVBAMTC1Nob3BBcmNoaXZlMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4lMslckj45tAWscRLSh31smZ8EF11r1WgUMyIauX2vj+PIIWLnS9iUJinY8XaA0/yTJ7bkjZCH8FNgY2H3YGIKNSMFAwHQYDVR0OBBYEFPJ3VY0ToQvFsSmyullXK6mu/YAuMBoGA1UdEQQTMBGCCWxvY2FsaG9zdIcEwKgBCjATBgNVHSUEDDAKBggrBgEFBQcDATAKBggqhkjOPQQDAgNJADBGAiEAgOf9Z4gpAc8LMv/0R4wVtcjfE5Z3pTOj/KcK6ajM264CIQC8HN+tD2lOWN3MZhrrxqYvSopCvy7kt9QhLjdWvGfnRg=="

/** The key as an uncompressed point (`04 || X || Y`), what `SecKeyCopyExternalRepresentation` returns for it. */
internal const val VECTOR_POINT_HEX =
    "04E2532C95C923E39B405AC7112D2877D6C999F04175D6BD5681433221AB97DAF8FE3C82162E74BD8942629D8F17680D3FC9327B6E48D9087F053606361F760620"

/** The JVM's `publicKey.encoded` for it (SubjectPublicKeyInfo DER). */
internal const val VECTOR_SPKI_HEX =
    "3059301306072A8648CE3D020106082A8648CE3D03010703420004E2532C95C923E39B405AC7112D2877D6C999F04175D6BD5681433221AB97DAF8FE3C82162E74BD8942629D8F17680D3FC9327B6E48D9087F053606361F760620"

/** SHA-256 of [VECTOR_SPKI_HEX]: the pin. */
internal const val VECTOR_PIN = "B499DD8ED5353BBC803E6FE5FF5183ECB660F78DCDCE8F55F587EDEF6644BBF5"

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
