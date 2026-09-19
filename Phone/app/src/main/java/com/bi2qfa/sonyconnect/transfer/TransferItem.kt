package com.bi2qfa.sonyconnect.transfer

import org.json.JSONArray
import org.json.JSONObject

enum class TransferState { QUEUED, RUNNING, DONE, FAILED }

data class TransferItem(
    val id: String,        // = 相机路径，天然唯一
    val name: String,
    val path: String,
    val size: Long,
    val state: TransferState = TransferState.QUEUED,
    val doneBytes: Long = 0, // 关联 .part 文件长度（断点续传基点）
    /**
     * 源文件在相机上的修改时间（`FTP LIST` 给的毫秒时间戳；0 = 不知道）。
     *
     * 在**入队时**从目录列表抄下来（见 `FilesScreen.startTransfer`）—— 落盘之后本地
     * 那个文件的 mtime 是"下载完成的时刻"，那是另一件事；用户要看的是照片本身的
     * 修改时间（拍下/改过的时间）。本字段出现之前持久化的老队列读出 0，
     * 界面就不显示这一行（不是错误，只是不知道）。
     */
    val mtime: Long = 0,
    /**
     * 落盘子目录名（`设备型号_SN码`，如 `ILCE-6300_05186914`）。
     *
     * 跟着条目走而不是现算：同名文件的归属是"哪台相机传下来的"，多设备适配后
     * 现算（读当前连接设备）会在切换设备/换相机后指向错的目录，续传更会写到
     * 另一台的 `.part` 上。
     */
    val deviceDir: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("path", path)
        .put("size", size)
        .put("state", state.name)
        .put("done", doneBytes)
        .put("mtime", mtime)
        .put("dir", deviceDir)

    companion object {
        fun fromJson(o: JSONObject): TransferItem = TransferItem(
            id = o.optString("id"),
            name = o.optString("name"),
            path = o.optString("path"),
            size = o.optLong("size"),
            state = runCatching { TransferState.valueOf(o.optString("state")) }
                .getOrDefault(TransferState.QUEUED),
            doneBytes = o.optLong("done"),
            mtime = o.optLong("mtime"),
            deviceDir = o.optString("dir"),
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
