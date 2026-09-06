package com.bi2qfa.sonyconnect.transfer

import org.json.JSONArray
import org.json.JSONObject

enum class TransferState { QUEUED, RUNNING, DONE, FAILED }

data class TransferItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val state: TransferState = TransferState.QUEUED,
    val doneBytes: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("path", path)
        .put("size", size)
        .put("state", state.name)
        .put("done", doneBytes)

    companion object {
        fun fromJson(o: JSONObject): TransferItem = TransferItem(
            id = o.optString("id"),
            name = o.optString("name"),
            path = o.optString("path"),
            size = o.optLong("size"),
            state = runCatching { TransferState.valueOf(o.optString("state")) }
                .getOrDefault(TransferState.QUEUED),
            doneBytes = o.optLong("done"),
        )
    }
}

object TransferJson {
    fun listToJson(items: List<TransferItem>): String {
        val arr = JSONArray()
        for (i in items) arr.put(i.toJson())
        return arr.toString()
    }

    fun listFromJson(s: String?): List<TransferItem> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { TransferItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
