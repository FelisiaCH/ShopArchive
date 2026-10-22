package xyz.felismp.shoparchive.app.flow

import kotlin.io.encoding.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.felismp.shoparchive.app.client.clientJson

/** A byte array as Base64 text: a slip photo can be hundreds of KB, which a number array would triple. */
internal object Base64Bytes : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.encode(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}

internal fun PendingSave.toText(): String = clientJson.encodeToString(PendingSave.serializer(), this)

/** Null for text that is not a save this version wrote: an old format or a damaged file is "nothing pending". */
internal fun pendingSaveFrom(text: String?): PendingSave? =
    if (text == null) null else try { clientJson.decodeFromString(PendingSave.serializer(), text) } catch (_: Exception) { null }
