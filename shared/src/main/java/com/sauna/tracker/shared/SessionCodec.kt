package com.sauna.tracker.shared

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(session: Session): ByteArray =
        json.encodeToString(Session.serializer(), session).toByteArray()

    fun decode(bytes: ByteArray): Session =
        json.decodeFromString(Session.serializer(), bytes.decodeToString())

    fun encodeSamples(samples: List<HrSample>): String =
        json.encodeToString(ListSerializer(HrSample.serializer()), samples)
}
