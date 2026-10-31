package com.ljyh.mei.data.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Gson's unsafe constructor does not apply Kotlin default parameters to old persisted metadata. */
class MediaMetadataAvailabilityAdapter : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != MediaMetadata::class.java) return null
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(writer: JsonWriter, value: T?) = delegate.write(writer, value)
            override fun read(reader: JsonReader): T? {
                val tree = JsonParser.parseReader(reader)
                if (tree.isJsonNull) return null
                if (tree.isJsonObject) {
                    val obj = tree.asJsonObject
                    val raw = obj.get("availability")?.takeIf { it.isJsonPrimitive }?.asString
                    if (raw !in SongPlaybackAvailability.entries.map { it.name }) {
                        obj.addProperty("availability", SongPlaybackAvailability.Unknown.name)
                        obj.remove("availabilityReason")
                    }
                }
                return delegate.fromJsonTree(tree)
            }
        }
    }
}
