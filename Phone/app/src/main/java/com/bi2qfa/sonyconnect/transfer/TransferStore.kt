package com.bi2qfa.sonyconnect.transfer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.bi2qfa.sonyconnect.core.StorageSink
import com.bi2qfa.sonyconnect.data.PairingStore
import org.json.JSONArray
import org.json.JSONObject

























object TransferStore {

    





    class DeviceQueue internal constructor(val guid: String) {
        internal val items: SnapshotStateList<TransferItem> =
            mutableListOf<TransferItem>().toMutableStateList()
    }

    private val queues = HashMap<String, DeviceQueue>()

    
    private val loaded = HashSet<String>()

    private var current = mutableStateOf(DeviceQueue(""))

    
    val currentQueue: DeviceQueue get() = current.value

    
    val items: SnapshotStateList<TransferItem> get() = current.value.items

    var batchRunning by mutableStateOf(false)
        private set

    var batchDoneBytes by mutableLongStateOf(0L)
        private set
    var batchTotalBytes by mutableLongStateOf(0L)
        private set
    var batchStartedAt by mutableLongStateOf(0L)
        private set
    var batchSpeedBps by mutableLongStateOf(0L)
        private set

    private lateinit var prefs: android.content.SharedPreferences

    
    private const val KEY_CAMERA_GUID = "cameraGuid"

    
    private const val KEY_QUEUES = "queues"

    
    private const val KEY_LEGACY_ITEMS = "items"

    




    private var legacyPending: List<TransferItem> = emptyList()

    fun load(context: Context) {
        prefs = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
        val guid = storedGuid()
        
        
        
        
        if (guid.isEmpty() || !PairingStore.contains(guid)) {
            legacyPending = restoreItems(prefs.getString(KEY_LEGACY_ITEMS, null))
            if (legacyPending.isNotEmpty()) {
                android.util.Log.d("TransferStore", "队列无主（$guid），待下一台设备认领 ${legacyPending.size} 条")
            }
            return
        }
        current.value = queueFor(guid)
    }

    private fun storedGuid(): String =
        if (!::prefs.isInitialized) "" else (prefs.getString(KEY_CAMERA_GUID, "") ?: "").lowercase()

    





    fun queueFor(guidHex: String): DeviceQueue {
        val key = guidHex.lowercase()
        queues[key]?.let { if (key in loaded) return it }
        val q = queues.getOrPut(key) { DeviceQueue(key) }
        if (loaded.add(key)) {
            
            
            
            
            q.items.addAll(restoreItems(bucketJson(key)))
        }
        return q
    }

    
    private fun restoreItems(json: String?): List<TransferItem> =
        TransferJson.listFromJson(json)
            .map { if (it.state == TransferState.RUNNING) it.copy(state = TransferState.QUEUED) else it }
            .distinctBy { it.id }

    private fun bucketJson(guid: String): String? {
        if (!::prefs.isInitialized) return null
        val all = runCatching { JSONObject(prefs.getString(KEY_QUEUES, "{}") ?: "{}") }.getOrNull() ?: return null
        return all.optString(guid, null)
    }

    private fun persist(q: DeviceQueue) {
        if (!::prefs.isInitialized) return
        if (q.guid.isEmpty()) return   
        
        runCatching {
            val all = runCatching { JSONObject(prefs.getString(KEY_QUEUES, "{}") ?: "{}") }
                .getOrDefault(JSONObject())
            all.put(q.guid, JSONArray(TransferJson.listToJson(q.items)))
            prefs.edit().putString(KEY_QUEUES, all.toString()).apply()
        }
    }

    private fun persist() = persist(current.value)

    
    fun persistNow() {
        persist(runningQueue ?: current.value)
    }

    








    fun onCameraChanged(guidHex: String) {
        if (guidHex.isBlank()) return
        if (!::prefs.isInitialized) return
        val key = guidHex.lowercase()
        if (current.value.guid == key) return
        val prev = current.value.guid
        persist(current.value)   
        prefs.edit().putString(KEY_CAMERA_GUID, key).apply()
        val q = queueFor(key)
        
        if (legacyPending.isNotEmpty()) {
            val add = legacyPending.filterNot { p -> q.items.any { it.id == p.id } }
            q.items.addAll(add)
            legacyPending = emptyList()
            persist(q)
        }
        current.value = q
        
        android.util.Log.d(
            "TransferStore",
            "队列切换：$prev → $key（${q.items.size} 条）",
        )
    }

    
    
    

    








    fun enqueue(q: DeviceQueue, item: TransferItem) {
        val stamped = if (item.deviceDir.isBlank()) {
            item.copy(deviceDir = StorageSink.deviceFolderFor(q.guid))
        } else {
            item
        }
        val idx = q.items.indexOfFirst { it.id == stamped.id }
        if (idx >= 0) {
            val ex = q.items[idx]
            if (ex.state == TransferState.QUEUED || ex.state == TransferState.RUNNING) return
            
            q.items[idx] = ex.copy(
                state = TransferState.QUEUED,
                doneBytes = if (ex.state == TransferState.DONE) 0L
                else ex.doneBytes.coerceIn(0, ex.size),
            )
            persist(q)
            return
        }
        q.items.add(0, stamped)
        persist(q)
    }

    fun enqueue(item: TransferItem) = enqueue(current.value, item)

    fun enqueueAll(q: DeviceQueue, newItems: List<TransferItem>) {
        for (i in newItems.asReversed()) enqueue(q, i)
    }

    fun enqueueAll(newItems: List<TransferItem>) = enqueueAll(current.value, newItems)

    fun remove(q: DeviceQueue, item: TransferItem) {
        q.items.removeAll { it.id == item.id }
        persist(q)
    }

    fun remove(item: TransferItem) = remove(current.value, item)

    fun update(q: DeviceQueue, item: TransferItem) {
        val idx = q.items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            q.items[idx] = item
            persist(q)
        }
    }

    fun update(item: TransferItem) = update(current.value, item)

    
    fun updateProgress(q: DeviceQueue, id: String, doneBytes: Long) {
        val idx = q.items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val it = q.items[idx]
            val d = if (it.size > 0) doneBytes.coerceIn(0, it.size) else doneBytes
            q.items[idx] = it.copy(doneBytes = d)
        }
    }

    fun updateProgress(id: String, doneBytes: Long) = updateProgress(current.value, id, doneBytes)

    fun clearFinished(q: DeviceQueue) {
        q.items.removeAll { it.state == TransferState.DONE }
        persist(q)
    }

    fun clearFinished() = clearFinished(current.value)

    
    fun clearAll(q: DeviceQueue) {
        q.items.clear()
        persist(q)
    }

    fun clearAll() = clearAll(current.value)

    
    fun resetFailedForStart(q: DeviceQueue) {
        for ((idx, it) in q.items.withIndex()) {
            if (it.state == TransferState.FAILED) {
                q.items[idx] = it.copy(
                    state = TransferState.QUEUED,
                    doneBytes = it.doneBytes.coerceIn(0, it.size),
                )
            }
        }
        persist(q)
    }

    fun resetFailedForStart() = resetFailedForStart(current.value)

    fun countBy(q: DeviceQueue, state: TransferState): Int = q.items.count { it.state == state }

    fun countBy(state: TransferState): Int = countBy(current.value, state)

    

    
    var runningQueue: DeviceQueue? = null
        private set

    private val batchIds = HashSet<String>()
    private val batchBase = HashMap<String, Long>()
    private var lastSampleAt = 0L
    private var lastSampleBytes = 0L

    fun markBatchStarted(q: DeviceQueue) {
        runningQueue = q
        batchRunning = true
        batchIds.clear()
        batchBase.clear()
        lastSampleAt = 0
        lastSampleBytes = 0
        batchStartedAt = System.currentTimeMillis()
        batchDoneBytes = 0
        batchTotalBytes = 0
        batchSpeedBps = 0
        for (it in q.items) {
            if (it.state == TransferState.DONE) continue
            val base = it.doneBytes.coerceIn(0, it.size)
            batchIds.add(it.id)
            batchBase[it.id] = base
            batchTotalBytes += it.size - base
        }
    }

    
    fun updateBatchProgress(q: DeviceQueue) {
        var done = 0L
        for (it in q.items) {
            if (it.id !in batchIds) {
                
                if (it.state == TransferState.DONE) continue
                val base = it.doneBytes.coerceIn(0, it.size)
                batchIds.add(it.id)
                batchBase[it.id] = base
                batchTotalBytes += it.size - base
                continue
            }
            val base = batchBase[it.id] ?: 0L
            done += it.doneBytes.coerceIn(0, it.size) - base
        }
        batchDoneBytes = done.coerceAtLeast(0)

        val now = System.currentTimeMillis()
        if (lastSampleAt == 0L) {
            lastSampleAt = now
            lastSampleBytes = done
            return
        }
        val dt = now - lastSampleAt
        if (dt >= 1000) {
            val instant = (done - lastSampleBytes).coerceAtLeast(0) * 1000 / dt
            batchSpeedBps = if (batchSpeedBps == 0L) instant
            else (batchSpeedBps * 3 + instant) / 4 
            lastSampleAt = now
            lastSampleBytes = done
        }
    }

    fun updateBatchProgress() = updateBatchProgress(runningQueue ?: current.value)

    fun markBatchEnded() {
        batchRunning = false
        runningQueue = null
        batchIds.clear()
        batchBase.clear()
    }

    
    fun requeueRunning(q: DeviceQueue) {
        for ((idx, it) in q.items.withIndex()) {
            if (it.state == TransferState.RUNNING) {
                q.items[idx] = it.copy(state = TransferState.QUEUED)
            }
        }
    }

    





    fun onConnectionLost() {
        if (!batchRunning) return
        (runningQueue ?: current.value).let { q ->
            requeueRunning(q)
            persist(q)
        }
        markBatchEnded()
    }
}
