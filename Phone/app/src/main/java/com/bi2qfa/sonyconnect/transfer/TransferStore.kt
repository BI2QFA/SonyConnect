package com.bi2qfa.sonyconnect.transfer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

object TransferStore {

    val items: SnapshotStateList<TransferItem> = mutableListOf<TransferItem>().toMutableStateList()

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

    fun load(context: Context) {
        prefs = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
        val restored = TransferJson.listFromJson(prefs.getString("items", null))
        items.clear()

        items.addAll(
            restored.map {
                if (it.state == TransferState.RUNNING) it.copy(state = TransferState.QUEUED) else it
            }.distinctBy { it.id }
        )
    }

    private fun persist() {

        runCatching { prefs.edit().putString("items", TransferJson.listToJson(items)).apply() }
    }

    fun persistNow() {
        persist()
    }

    fun enqueue(item: TransferItem) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            val ex = items[idx]
            if (ex.state == TransferState.QUEUED || ex.state == TransferState.RUNNING) return

            items[idx] = ex.copy(
                state = TransferState.QUEUED,
                doneBytes = if (ex.state == TransferState.DONE) 0L
                else ex.doneBytes.coerceIn(0, ex.size),
            )
            persist()
            return
        }
        items.add(0, item)
        persist()
    }

    fun enqueueAll(newItems: List<TransferItem>) {
        for (i in newItems.asReversed()) enqueue(i)
    }

    fun remove(item: TransferItem) {
        items.removeAll { it.id == item.id }
        persist()
    }

    fun update(item: TransferItem) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            items[idx] = item
            persist()
        }
    }

    fun updateProgress(id: String, doneBytes: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val it = items[idx]
            val d = if (it.size > 0) doneBytes.coerceIn(0, it.size) else doneBytes
            items[idx] = it.copy(doneBytes = d)
        }
    }

    fun clearFinished() {
        items.removeAll { it.state == TransferState.DONE }
        persist()
    }

    fun clearAll() {
        items.clear()
        persist()
    }

    fun resetFailedForStart() {
        for ((idx, it) in items.withIndex()) {
            if (it.state == TransferState.FAILED) {
                items[idx] = it.copy(
                    state = TransferState.QUEUED,
                    doneBytes = it.doneBytes.coerceIn(0, it.size),
                )
            }
        }
        persist()
    }

    fun hasPending(): Boolean = items.any {
        it.state == TransferState.QUEUED || it.state == TransferState.RUNNING
    }

    fun countBy(state: TransferState): Int = items.count { it.state == state }

    private val batchIds = HashSet<String>()
    private val batchBase = HashMap<String, Long>()
    private var lastSampleAt = 0L
    private var lastSampleBytes = 0L

    fun markBatchStarted() {
        batchRunning = true
        batchIds.clear()
        batchBase.clear()
        lastSampleAt = 0
        lastSampleBytes = 0
        batchStartedAt = System.currentTimeMillis()
        batchDoneBytes = 0
        batchTotalBytes = 0
        batchSpeedBps = 0
        for (it in items) {
            if (it.state == TransferState.DONE) continue
            val base = it.doneBytes.coerceIn(0, it.size)
            batchIds.add(it.id)
            batchBase[it.id] = base
            batchTotalBytes += it.size - base
        }
    }

    fun updateBatchProgress() {
        var done = 0L
        for (it in items) {
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

    fun markBatchEnded() {
        batchRunning = false
        batchIds.clear()
        batchBase.clear()
    }

    fun onConnectionLost() {
        if (!batchRunning) return
        for ((idx, it) in items.withIndex()) {
            if (it.state == TransferState.RUNNING) {
                items[idx] = it.copy(state = TransferState.QUEUED)
            }
        }
        markBatchEnded()
    }
}
