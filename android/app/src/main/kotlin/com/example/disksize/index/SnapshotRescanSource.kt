package com.example.disksize.index

import com.example.disksize.scan.SnapshotRescanContract as Contract
import org.json.JSONObject
import java.nio.ByteBuffer

/** Scope can only originate in a revision-checked index, never in a client filesystem path. */
internal data class SnapshotRescanSource(val root: String, val backend: String, val path: ByteArray,
    val identities: LongArray, val filesystem: Long) {
    companion object {
        fun read(store: IndexStore, job: SnapshotRescanStore.Job): SnapshotRescanSource {
            fun reject(reason: Contract.Error): Nothing = throw SnapshotRescanStore.Rejected(reason)
            val db = store.readableDatabase
            var root = ""; var backend = ""; var identity = JSONObject(); var filesystem = 0L
            db.rawQuery("""SELECT s.root,s.backend,s.identity,s.metrics,p.revision,s.revision,p.retained,s.provisional,s.state
                FROM snapshots p JOIN sessions s ON s.id=p.sid WHERE p.id=? AND s.id=?""",
                arrayOf(job.snapshotId, job.sessionId)).use {
                if (!it.moveToFirst()) reject(Contract.Error.SNAPSHOT_UNAVAILABLE)
                if (it.getLong(4) != job.expectedRevision || it.getLong(5) != job.expectedRevision) reject(Contract.Error.STALE_REVISION)
                if (it.getInt(6) != 0) reject(Contract.Error.RETAINED)
                if (it.getInt(7) != 0 || it.getString(8) !in setOf("completed", "partial")) reject(Contract.Error.SNAPSHOT_UNAVAILABLE)
                root = it.getString(0); backend = it.getString(1); identity = JSONObject(it.getString(2))
                filesystem = JSONObject(it.getString(3)).optLong("rootFilesystemType")
            }
            val live = store.currentIdentity(root)
            if (backend !in setOf("ordinary", "root") || !root.startsWith('/') || root.contains('\u0000') ||
                root.toByteArray().size > 4096 || filesystem == 0L ||
                listOf("rules", "boot").any { identity.optString(it).isEmpty() || identity.optString(it) != live.optString(it) } ||
                !identity.has("user") || identity.optInt("user", -1) != live.getInt("user") ||
                (identity.optString("volumeUuid").isNotEmpty() && identity.optString("volumeUuid") != live.optString("volumeUuid")))
                reject(Contract.Error.INCOMPATIBLE_IDENTITY)
            val chain = mutableListOf<LongArray>(); val names = mutableListOf<ByteArray>()
            var node = job.targetNode; var expectedDepth = -1
            while (node != 0L && chain.size <= 512) {
                db.rawQuery("SELECT parent,depth,name,path,device,inode,mount,kind,valid,skip FROM nodes WHERE sid=? AND id=?",
                    arrayOf(job.sessionId, node.toString())).use {
                    if (!it.moveToFirst()) reject(Contract.Error.NODE_NOT_FOUND)
                    val depth = it.getInt(1)
                    if (expectedDepth < 0) expectedDepth = depth
                    if (depth != expectedDepth || depth !in 0..512 || it.getInt(7) != 1 || it.getInt(8) != 1 || it.getInt(9) != 0 ||
                        it.getBlob(4).size != 8 || it.getBlob(5).size != 8 || it.getLong(6) <= 0)
                        reject(Contract.Error.INCOMPATIBLE_IDENTITY)
                    if (chain.isEmpty() && !it.getBlob(3).contentEquals(job.targetPath)) reject(Contract.Error.STALE_REVISION)
                    chain.add(longArrayOf(ByteBuffer.wrap(it.getBlob(4)).long, ByteBuffer.wrap(it.getBlob(5)).long, it.getLong(6)))
                    if (depth > 0) names.add(it.getBlob(2))
                    else if (node != 1L || it.getLong(0) != 0L || it.getBlob(3).isNotEmpty()) reject(Contract.Error.PROTOCOL_ERROR)
                    node = it.getLong(0); expectedDepth--
                }
            }
            if (node != 0L || expectedDepth != -1 || chain.isEmpty()) reject(Contract.Error.PROTOCOL_ERROR)
            val components = names.asReversed()
            val reconstructed = java.io.ByteArrayOutputStream().also { out -> components.forEachIndexed { i, name ->
                if (i > 0) out.write(47); out.write(name)
            } }.toByteArray()
            if (!reconstructed.contentEquals(job.targetPath)) reject(Contract.Error.PROTOCOL_ERROR)
            val ordered = chain.asReversed()
            if (ordered.any { it[0] != ordered[0][0] || it[2] != ordered[0][2] }) reject(Contract.Error.INCOMPATIBLE_IDENTITY)
            return SnapshotRescanSource(root, backend, job.targetPath, ordered.flatMap { it.toList() }.toLongArray(), filesystem)
        }
    }
}
