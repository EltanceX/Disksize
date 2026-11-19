package com.example.disksize.scan

import com.example.disksize.index.IndexStore
import com.example.disksize.index.SnapshotRescanStore
import com.example.disksize.index.hex
import org.json.JSONObject

internal object SnapshotRescanStatus {
    fun encode(job: SnapshotRescanStore.Job): JSONObject = JSONObject().put("status", "ok")
        .put("taskId", job.id).put("snapshotId", job.snapshotId).put("sessionId", job.sessionId)
        .put("targetNodeId", job.targetNode).put("expectedRevision", job.expectedRevision)
        .put("targetPathHex", job.targetPath.take(4096).toByteArray().hex()).put("pathTruncated", job.targetPath.size > 4096)
        .put("state", job.state.wireName).put("error", job.error).put("active", !job.state.terminal)
        .put("createdAtMillis", job.created).put("startedAtMillis", job.started).put("scannedAtMillis", job.scanned)
        .put("endedAtMillis", job.ended).put("nodeCount", job.nodes).put("issueCount", job.issues)
        .put("committedRevision", job.committedRevision ?: JSONObject.NULL).put("cancellationRequested", job.cancellationRequested)
        .put("stagingCleaned", job.stagingCleaned)

    /** Only reconcile the journal's own session; a historical update cannot replace a newer full scan. */
    fun alignJournal(store: IndexStore, value: JSONObject?): JSONObject? {
        if (value == null || value.optBoolean("active")) return value
        val id = value.optString("sessionId")
        val metadata = store.readableDatabase.rawQuery("SELECT revision,local_updated,local_updates FROM snapshots WHERE sid=?", arrayOf(id)).use {
            if (!it.moveToFirst() || it.getLong(2) == 0L) return value
            longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2))
        }
        if (value.optLong("contentRevision", -1) == metadata[0]) return value
        val preview = store.preview(id)
        return JSONObject(value.toString()).also { aligned ->
            for (key in listOf("state", "nodeCount", "issueCount", "logicalBytes", "allocatedBytes", "provisional",
                "complete", "entries", "issues", "previewTruncated")) aligned.put(key, preview.get(key))
            aligned.put("contentRevision", metadata[0]).put("lastLocalUpdateAtMillis", metadata[1])
                .put("localUpdateCount", metadata[2]).put("updatedAtMillis", metadata[1])
        }
    }
}
