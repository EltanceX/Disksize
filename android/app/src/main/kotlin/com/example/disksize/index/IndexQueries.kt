package com.example.disksize.index

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Bounded pages and ancestor chains; no tree or unbounded cursor history in memory. */
internal class IndexQueries(private val store: IndexStore) {
    fun query(request: String, nonBlockingSnapshots: Boolean = false): String = try { execute(JSONObject(request), nonBlockingSnapshots).toString() }
        catch (_: IllegalArgumentException) { JSONObject().put("status", "invalid-query").toString() }
        catch (_: org.json.JSONException) { JSONObject().put("status", "invalid-query").toString() }

    private fun execute(q: JSONObject, nonBlockingSnapshots: Boolean): JSONObject {
        val kind = q.getString("kind")
        if (kind.startsWith("snapshot-")) return store.snapshots.execute(q, waitForMutation = !nonBlockingSnapshots)
        val db = store.readableDatabase
        if (kind == "recent") {
            val sessions = JSONArray()
            db.rawQuery("SELECT id FROM sessions ORDER BY started DESC,id DESC LIMIT 20", null).use {
                while (it.moveToNext()) metadata(db, it.getString(0))?.let(sessions::put)
            }
            return JSONObject().put("status", "ok").put("sessions", sessions)
        }
        val id = q.getString("sessionId"); require(id.length == 36)
        require(kind in setOf("session", "children", "large-files", "detail", "issues", "ancestors", "treemap", "locate"))
        val parent = q.optLong("parentId"); val node = q.optLong("nodeId")
        val sort = q.optString("sort", "logical"); require(sort in setOf("logical", "allocated", "name"))
        val limit = q.optInt("limit", 100); require(limit in 1..100 && parent >= 0 && node >= 0)
        require(kind !in setOf("children", "treemap") || parent > 0); require(kind !in setOf("detail", "ancestors") || node > 0)
        require(kind != "large-files" || sort != "name")
        val token = q.optString("cursor"); require(token.length <= 4096)
        require(kind != "treemap" || (sort != "name" && token.isEmpty()))
        val meta = metadata(db, id) ?: return JSONObject().put("status", "not-found")
        val revision = meta.getLong("revision")
        if (q.has("expectedRevision") && q.getLong("expectedRevision") != revision) return meta.put("status", "stale-cursor")
        val signature = "$id|$kind|$parent|$node|$sort|$limit"
        val cursor = if (token.isEmpty()) null else JSONObject(String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)).also {
            require(it.getInt("version") == 2 && it.getString("query") == signature && it.getString("direction") in setOf("after", "before"))
        }
        if (cursor != null && cursor.getLong("revision") != revision) return meta.put("status", "stale-cursor")
        val result = meta.put("status", "ok").put("entries", JSONArray()).put("issues", JSONArray())
        when (kind) {
            "locate" -> {
                // Resolve raw components relative to a known directory. Requests stay below the
                // bridge's 8 KiB bound even for a 512-component path by resolving in chunks.
                val hex = q.getString("pathHex")
                require(parent > 0 && hex.length <= 6000 && hex.length % 2 == 0 && hex.matches(Regex("[0-9a-f]*")))
                val suffix = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                if (suffix.isNotEmpty()) require(suffix.splitComponentsValid())
                val base = db.rawQuery("SELECT path FROM nodes WHERE sid=? AND id=? AND kind=1", arrayOf(id, parent.toString())).use {
                    if (!it.moveToFirst()) return meta.put("status", "not-found")
                    it.getBlob(0)
                }
                var path = if (base.isEmpty()) suffix else if (suffix.isEmpty()) base else base + byteArrayOf(47) + suffix
                var exact = true
                while (true) {
                    val found = db.rawQuery("""SELECT n.*,a.logical,a.allocated AS total_allocated,a.complete AS subtree_complete,a.owner,
                        EXISTS(SELECT 1 FROM issues i WHERE i.sid=n.sid AND i.node=n.id AND i.code=1) AS inaccessible
                        FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id
                        WHERE n.sid=? AND n.path=X'${path.hex()}'""", arrayOf(id)).use {
                        if (!it.moveToFirst() || (!exact && it.getInt(it.getColumnIndexOrThrow("kind")) != 1)) false
                        else { result.put("detail", detail(it)).put("pathMatched", exact); true }
                    }
                    if (found) break
                    if (path.contentEquals(base)) { result.put("status", "not-found"); break }
                    val slash = path.lastIndexOf(47.toByte())
                    path = if (slash < 0) byteArrayOf() else path.copyOf(slash)
                    exact = false
                }
            }
            "detail" -> db.rawQuery("""SELECT n.*,a.logical,a.allocated AS total_allocated,a.complete AS subtree_complete,a.owner,
                EXISTS(SELECT 1 FROM issues i WHERE i.sid=n.sid AND i.node=n.id AND i.code=1) AS inaccessible
                FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id WHERE n.sid=? AND n.id=?""", arrayOf(id, node.toString())).use {
                if (!it.moveToFirst()) result.put("status", "not-found") else result.put("detail", detail(it))
            }
            "ancestors" -> {
                val rows = JSONArray()
                db.rawQuery("""WITH RECURSIVE chain(id,parent,name,depth) AS (
                    SELECT id,parent,name,depth FROM nodes WHERE sid=? AND id=? UNION ALL
                    SELECT n.id,n.parent,n.name,n.depth FROM nodes n JOIN chain c ON n.id=c.parent WHERE n.sid=? AND c.depth>0)
                    SELECT id,parent,name FROM chain ORDER BY depth LIMIT 513""", arrayOf(id, node.toString(), id)).use {
                    while (it.moveToNext()) rows.put(JSONObject().put("id", it.getLong(0)).put("parentId", it.getLong(1)).put("nameHex", it.getBlob(2).hex()))
                }
                result.put("ancestors", rows)
                if (rows.length() == 0) result.put("status", "not-found")
            }
            "issues" -> issues(db, id, node, limit, cursor, signature, revision, result)
            "children", "large-files" -> entries(db, id, kind, parent, sort, limit, cursor, signature, revision, result)
            "treemap" -> {
                val depth = q.optInt("depth", 3); require(depth in 0..6)
                treemap(db, id, parent, sort, limit, depth, signature, revision, result)
            }
        }
        // Every indexed write advances revision atomically. Only accept a stable read interval.
        val currentRevision = db.rawQuery("SELECT revision FROM sessions WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        if (currentRevision != revision) return JSONObject().put("status", "stale-cursor")
        return result
    }
    private fun treemap(db: SQLiteDatabase, id: String, parent: Long, sort: String, limit: Int, depth: Int, signature: String, revision: Long, result: JSONObject) {
        // In-progress aggregates are not additive yet. Never draw them as proportions.
        if (result.getBoolean("provisional")) return
        val total = db.rawQuery("SELECT $sort FROM aggregates WHERE sid=? AND id=? AND kind=1", arrayOf(id, parent.toString())).use {
            if (!it.moveToFirst()) { result.put("status", "not-found"); return }; it.getLong(0)
        }
        if (depth > 0) entries(db, id, "children", parent, sort, limit, null, signature, revision, result)
        result.remove("nextCursor"); result.remove("previousCursor")
        val rows = result.getJSONArray("entries")
        var represented = 0L
        val key = if (sort == "logical") "logicalBytes" else "allocatedBytes"
        for (i in 0 until rows.length()) represented = Math.addExact(represented, rows.getJSONObject(i).getLong(key))
        if (represented > total) { result.put("status", "inconsistent-index"); return }
        db.rawQuery("""SELECT COUNT(*),COALESCE(SUM(a.$sort=0),0),COALESCE(SUM(a.complete=0 OR n.valid=0),0)
            FROM aggregates a JOIN nodes n ON n.sid=a.sid AND n.id=a.id WHERE a.sid=? AND a.parent=?""",
            arrayOf(id, parent.toString())).use {
            check(it.moveToFirst())
            result.put("treemap", JSONObject().put("totalBytes", total).put("otherBytes", total - represented)
                .put("childCount", it.getLong(0)).put("zeroCount", it.getLong(1)).put("unknownCount", it.getLong(2)))
        }
        val summary = result.getJSONObject("treemap").put("depth", depth)
        val groups = JSONArray()
        data class Candidate(val node: Long, val level: Int, val bytes: Long)
        val pending = java.util.PriorityQueue<Candidate>(compareByDescending<Candidate> { it.bytes }.thenBy { it.node })
        fun enqueue(items: JSONArray, level: Int) {
            if (level >= depth) return
            for (i in 0 until items.length()) {
                val row = items.getJSONObject(i)
                if (row.getString("kind") == "directory" && row.getLong(key) > 0)
                    pending.add(Candidate(row.getLong("id"), level, row.getLong(key)))
            }
        }
        enqueue(rows, 1)
        var count = rows.length(); var budget = rows.toString().length
        var truncated = depth > 0 && summary.getLong("childCount") > rows.length()
        // Global node, branch and serialized-text budgets apply to the entire hierarchy.
        // The existing children indexes keep each expansion bounded independently.
        while (pending.isNotEmpty() && count < 300 && groups.length() < 48 && budget < 300000) {
            val candidate = pending.remove()
            val branch = JSONObject()
            entries(db, id, "children", candidate.node, sort, minOf(limit, 300 - count), null, signature, revision, branch)
            val children = branch.getJSONArray("entries")
            branch.remove("nextCursor"); branch.remove("previousCursor")
            val representedBytes = (0 until children.length()).fold(0L) { sum, i -> Math.addExact(sum, children.getJSONObject(i).getLong(key)) }
            if (representedBytes > candidate.bytes) { result.put("status", "inconsistent-index"); return }
            val childCount = db.rawQuery("SELECT COUNT(*) FROM aggregates WHERE sid=? AND parent=?", arrayOf(id, candidate.node.toString())).use {
                it.moveToFirst(); it.getLong(0)
            }
            branch.put("parentId", candidate.node).put("totalBytes", candidate.bytes).put("childCount", childCount)
            val branchSize = branch.toString().length
            if (budget + branchSize > 300000) { truncated = true; break }
            groups.put(branch); budget += branchSize; count += children.length()
            if (childCount > children.length()) truncated = true
            enqueue(children, candidate.level + 1)
        }
        summary.put("groups", groups).put("nodeCount", count).put("truncated", truncated || pending.isNotEmpty())
    }
    private data class Row(val value: JSONObject, val id: Long, val key: String)
    private fun pages(rows: MutableList<Row>, limit: Int, cursor: JSONObject?, signature: String, revision: Long, result: JSONObject, field: String) {
        val before = cursor?.getString("direction") == "before"
        val extra = rows.size > limit
        if (extra) rows.removeAt(rows.lastIndex)
        if (before) rows.reverse()
        result.put(field, JSONArray(rows.map { it.value }))
        if (rows.isEmpty()) return
        if (if (before) extra else cursor != null) result.put("previousCursor", encode(signature, revision, rows.first(), "before"))
        if (if (before) cursor != null else extra) result.put("nextCursor", encode(signature, revision, rows.last(), "after"))
    }
    private fun issues(db: SQLiteDatabase, id: String, node: Long, limit: Int, cursor: JSONObject?, signature: String, revision: Long, result: JSONObject) {
        val before = cursor?.getString("direction") == "before"
        val args = mutableListOf(id)
        val continuation = if (cursor == null) "" else { val last = cursor.getLong("lastId"); require(last > 0); args.add(last.toString()); " AND id${if (before) "<" else ">"}?" }
        val filter = if (node == 0L) "" else { args.add(node.toString()); " AND node=?" }
        args.add((limit + 1).toString())
        val rows = mutableListOf<Row>()
        db.rawQuery("SELECT id,node,code,errno,operation FROM issues WHERE sid=?$continuation$filter ORDER BY id ${if (before) "DESC" else "ASC"} LIMIT ?", args.toTypedArray()).use {
            while (it.moveToNext()) rows.add(Row(JSONObject().put("id", it.getLong(0)).put("nodeId", it.getLong(1)).put("code", errors[it.getInt(2)])
                .put("systemError", it.getInt(3)).put("operation", it.getString(4)), it.getLong(0), ""))
        }
        pages(rows, limit, cursor, signature, revision, result, "issues")
    }
    private fun entries(db: SQLiteDatabase, id: String, kind: String, parent: Long, sort: String, limit: Int, cursor: JSONObject?, signature: String, revision: Long, result: JSONObject) {
        val before = cursor?.getString("direction") == "before"
        val ascending = (sort == "name") != before
        val column = if (sort == "name") "name_key" else sort
        val args = mutableListOf(id)
        var from = "aggregates a JOIN nodes n ON n.sid=a.sid AND n.id=a.id"
        var filter = if (kind == "children") { args.add(parent.toString()); "a.parent=?" } else "a.kind=2"
        if (kind == "large-files" && parent > 1) {
            val path = db.rawQuery("SELECT path FROM nodes WHERE sid=? AND id=? AND kind=1", arrayOf(id, parent.toString())).use {
                if (!it.moveToFirst()) { result.put("status", "not-found"); return }; it.getBlob(0)
            }
            // Controlled BLOB literals preserve raw byte ordering; force a subtree range scan
            // before sorting, rather than walking the whole volume's large-file index.
            from = "nodes n INDEXED BY node_path CROSS JOIN aggregates a ON a.sid=n.sid AND a.id=n.id"
            filter += " AND n.sid=? AND n.path>=X'${(path + byteArrayOf(47)).hex()}' AND n.path<X'${(path + byteArrayOf(48)).hex()}'"
            args.add(id)
        }
        val continuation = if (cursor == null) "" else {
            val last = cursor.getLong("lastId"); require(last > 0)
            val key = cursor.getString("key")
            if (sort == "name") require(key.length <= 510 && key.matches(Regex("[0-9a-f]*"))) else require(key.toLong() >= 0)
            args.add(key); args.add(last.toString()); " AND (a.$column,a.id)${if (ascending) ">" else "<"}(?,?)"
        }
        args.add((limit + 1).toString())
        val order = if (ascending) "ASC" else "DESC"
        val rows = mutableListOf<Row>()
        db.rawQuery("""SELECT a.id,a.parent,n.name,a.kind,a.logical,a.allocated,a.complete,a.owner,a.$column,n.valid,n.skip,
            EXISTS(SELECT 1 FROM issues i WHERE i.sid=n.sid AND i.node=n.id AND i.code=1)
            FROM $from WHERE a.sid=? AND $filter$continuation ORDER BY a.$column $order,a.id $order LIMIT ?""", args.toTypedArray()).use {
            while (it.moveToNext()) rows.add(Row(JSONObject().put("id", it.getLong(0)).put("parentId", it.getLong(1)).put("nameHex", it.getBlob(2).hex())
                .put("kind", kinds[it.getInt(3)]).put("logicalBytes", it.getLong(4)).put("allocatedBytes", it.getLong(5))
                .put("complete", it.getInt(6) == 1).put("ownerId", it.getLong(7)).put("metadataValid", it.getInt(9) == 1)
                .put("skipped", skips[it.getInt(10)]).put("inaccessible", it.getInt(11) == 1), it.getLong(0), it.getString(8)))
        }
        pages(rows, limit, cursor, signature, revision, result, "entries")
    }
    private fun ByteArray.splitComponentsValid(): Boolean =
        !contains(0) && first() != 47.toByte() && last() != 47.toByte() &&
            toList().let { bytes ->
                var start = 0
                for (end in 0..size) if (end == size || bytes[end] == 47.toByte()) {
                    val part = copyOfRange(start, end)
                    if (part.isEmpty() || part.size > 255 || part.contentEquals(byteArrayOf(46)) || part.contentEquals(byteArrayOf(46, 46))) return false
                    start = end + 1
                }
                true
            }

    private fun metadata(db: SQLiteDatabase, id: String): JSONObject? = db.rawQuery(
        """SELECT s.revision,s.provisional,s.state,s.phase,s.nodes,s.issues,s.metrics,s.root,s.backend,s.started,s.ended,s.volume,s.reason,
            p.id,p.retained,p.local_updated,p.local_updates FROM sessions s LEFT JOIN snapshots p ON p.sid=s.id WHERE s.id=?""", arrayOf(id)).use {
        if (!it.moveToFirst()) null else JSONObject().put("sessionId", id).put("revision", it.getLong(0)).put("provisional", it.getInt(1) == 1)
            .put("state", it.getString(2)).put("phase", it.getString(3)).put("nodeCount", it.getLong(4)).put("issueCount", it.getLong(5))
            .put("schemaVersion", IndexStore.SCHEMA_VERSION).put("metrics", JSONObject(it.getString(6))).put("rootPath", it.getString(7))
            .put("backend", it.getString(8)).put("startedAtMillis", it.getLong(9)).put("endedAtMillis", it.getLong(10))
            .put("volume", JSONObject(it.getString(11))).put("reason", it.getString(12)).also { value ->
                if (!it.isNull(13)) {
                    value.put("snapshotId", it.getString(13)).put("retained", it.getInt(14) == 1)
                        .put("lastLocalUpdateAtMillis", it.getLong(15)).put("localUpdateCount", it.getLong(16))
                    store.rescans.decorate(db, value, it.getString(13))
                }
            }
    }
    private fun detail(c: Cursor): JSONObject {
        fun long(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        fun bytes(name: String) = c.getBlob(c.getColumnIndexOrThrow(name)).hex()
        return JSONObject().put("id", long("id")).put("parentId", long("parent")).put("nameHex", bytes("name"))
            .put("pathHex", bytes("path")).put("symlinkTargetHex", bytes("link")).put("kind", kinds[long("kind").toInt()])
            .put("logicalBytes", long("logical")).put("allocatedBytes", long("total_allocated"))
            .put("complete", long("subtree_complete") == 1L).put("ownerId", long("owner"))
            .put("mode", long("mode")).put("uid", long("uid")).put("gid", long("gid"))
            .put("deviceHex", bytes("device")).put("inodeHex", bytes("inode")).put("mountId", long("mount"))
            .put("linkCount", long("nlink")).put("rawSize", long("size")).put("rawAllocatedBytes", long("allocated"))
            .put("mtimeSeconds", long("seconds")).put("mtimeNanos", long("nanos")).put("metadataValid", long("valid") == 1L)
            .put("skipped", skips[long("skip").toInt()]).put("inaccessible", long("inaccessible") == 1L)
    }
    private fun encode(query: String, revision: Long, row: Row, direction: String): String = Base64.encodeToString(
        JSONObject().put("version", 2).put("query", query).put("revision", revision).put("lastId", row.id).put("key", row.key).put("direction", direction)
            .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
}
