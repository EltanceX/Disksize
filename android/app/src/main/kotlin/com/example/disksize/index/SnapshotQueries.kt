package com.example.disksize.index

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** One disposable on-disk comparison. Each call merges at most 512 raw-byte paths. */
internal class SnapshotQueries(private val store: IndexStore) {
    private var builder: Builder? = null
    fun execute(q: JSONObject, waitForMutation: Boolean = true): JSONObject {
        val lock = store.snapshotMutationLock
        if (waitForMutation) lock.lock() else if (!lock.tryLock()) return response("busy")
        return try { executeLocked(q) } finally { lock.unlock() }
    }
    private fun executeLocked(q: JSONObject): JSONObject {
        val db = store.writableDatabase
        return when (q.getString("kind")) {
            "snapshot-list" -> list(db, q)
            "snapshot-retain" -> mutate(db) {
                val id = uuid(q, "snapshotId")
                val snapshot = snapshot(db, id) ?: return@mutate response("not-found")
                if (store.rescans.hasActive(db, id)) return@mutate response("busy")
                db.execSQL("UPDATE snapshots SET retained=? WHERE id=?", arrayOf<Any>(if (q.getBoolean("retained")) 1 else 0, id))
                response("ok").put("snapshot", snapshot.put("retained", q.getBoolean("retained")))
            }
            "snapshot-delete" -> mutate(db) {
                val id = uuid(q, "snapshotId")
                val snapshot = snapshot(db, id) ?: return@mutate response("not-found")
                if (store.rescans.hasActive(db, id)) return@mutate response("busy")
                if (snapshot.getBoolean("retained")) return@mutate response("retained")
                erase(db, id)
                response("ok")
            }
            "snapshot-compare" -> compare(db, q)
            else -> response("invalid-query")
        }
    }
    internal fun invalidateForRescan(db: SQLiteDatabase, check: () -> Unit) {
        kotlin.check(store.snapshotMutationLock.isHeldByCurrentThread && db.inTransaction())
        db.compileStatement("DELETE FROM diff_rows WHERE path IN (SELECT path FROM diff_rows LIMIT 4096)").use { delete ->
            do { check(); val rows = delete.executeUpdateDelete() } while (rows == 4096)
        }
        check()
        db.execSQL("DELETE FROM diff_cache"); builder = null
    }
    private fun response(status: String) = JSONObject().put("status", status)
    private fun uuid(q: JSONObject, key: String): String = q.getString(key).also {
        require(it.matches(Regex("[0-9a-fA-F-]{36}")))
    }
    private fun mutate(db: SQLiteDatabase, block: () -> JSONObject): JSONObject {
        db.beginTransactionNonExclusive()
        var failure: Throwable? = null
        return try { block().also { db.setTransactionSuccessful() } }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { db.endTransaction() }
            catch (error: Exception) { if (failure == null) throw error else failure.addSuppressed(error) }
        }
    }
    private fun erase(db: SQLiteDatabase, id: String) = withDeletionSpillThreshold(db) {
        // IDs originate in this database, never filesystem paths. Targets are never touched.
        db.execSQL("DELETE FROM diff_rows"); db.execSQL("DELETE FROM diff_cache"); builder = null
        db.execSQL("DELETE FROM snapshots WHERE id=?", arrayOf(id))
        for (table in listOf("issues", "hardlinks", "aggregates", "nodes")) db.execSQL("DELETE FROM $table WHERE sid=?", arrayOf(id))
        db.execSQL("DELETE FROM sessions WHERE id=?", arrayOf(id))
    }
    private fun withDeletionSpillThreshold(db: SQLiteDatabase, block: () -> Unit) {
        // This runs on the transaction's pinned writer connection. Android's 8 MiB
        // soft heap limit otherwise repeatedly spills and reloads dirty index pages.
        // Keep spilling enabled, with a finite 32 MiB threshold for this deletion only.
        fun pragma(name: String) = db.compileStatement("PRAGMA $name").use { it.simpleQueryForLong() }
        val previous = pragma("cache_spill")
        val threshold = maxOf(1L, 32L * 1024 * 1024 / pragma("page_size"))
        if (previous == 0L || previous >= threshold) { block(); return }
        fun setThreshold(pages: Long) {
            db.execSQL("PRAGMA cache_spill=$pages")
            // SQLite 3.44 truncates numeric booleans to 8 bits. Explicitly enable
            // spilling after setting a threshold that may be a multiple of 256.
            db.execSQL("PRAGMA cache_spill=ON")
        }
        var failure: Throwable? = null
        try { setThreshold(threshold); block() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { setThreshold(previous) }
            catch (error: Exception) { if (failure == null) throw error else failure.addSuppressed(error) }
        }
    }
    private fun list(db: SQLiteDatabase, q: JSONObject): JSONObject {
        val limit = q.optInt("limit", 20); require(limit in 1..50)
        val token = q.optString("cursor"); require(token.length <= 4096)
        val args = mutableListOf<String>()
        val after = if (token.isEmpty()) "" else {
            val c = decode(token); require(c.getInt("version") == 1 && c.getString("kind") == "snapshots")
            args.add(c.getLong("created").toString()); args.add(uuid(c, "snapshotId")); "WHERE (created,id)<(?,?)"
        }
        args.add((limit + 1).toString())
        val storage = storageEstimate(db)
        val result = JSONArray(); var next: String? = null
        db.rawQuery("SELECT id,created FROM snapshots $after ORDER BY created DESC,id DESC LIMIT ?", args.toTypedArray()).use {
            while (it.moveToNext()) {
                if (result.length() == limit) break
                snapshot(db, it.getString(0), storage)?.let(result::put)
                if (result.length() == limit && !it.isLast) next = encode(JSONObject().put("version", 1).put("kind", "snapshots")
                    .put("created", it.getLong(1)).put("snapshotId", it.getString(0)))
            }
        }
        return response("ok").put("snapshots", result).put("nextCursor", next)
    }
    // All sessions share table/index pages. Attribute used pages by stored
    // node/issue counts, including unfinished sessions and isolated rescan staging; this is not reclaimable
    // filesystem space or an exact measurement of an individual snapshot.
    private data class StorageEstimate(val usedBytes: Long, val records: Long) {
        fun bytes(nodes: Long, issues: Long): Long =
            if (records == 0L) 0L else (usedBytes * ((nodes + issues + 1).toDouble() / records)).roundToLong().coerceIn(0, usedBytes)
    }
    private fun storageEstimate(db: SQLiteDatabase): StorageEstimate {
        fun pragma(name: String) = db.rawQuery("PRAGMA $name", null).use { it.moveToFirst(); it.getLong(0) }
        val used = (pragma("page_count") - pragma("freelist_count")).coerceAtLeast(0) * pragma("page_size")
        val records = db.rawQuery("""SELECT (SELECT COALESCE(SUM(nodes+issues+1),0) FROM sessions)+
            (SELECT COALESCE(SUM(CASE WHEN staging_cleaned=0 THEN nodes+issues+1 ELSE 1 END),0) FROM rescan_jobs)""",
            null).use { it.moveToFirst(); it.getLong(0) }
        return StorageEstimate(used, records)
    }
    private fun snapshot(db: SQLiteDatabase, id: String, storage: StorageEstimate? = null): JSONObject? = db.rawQuery("""SELECT p.*,s.root,s.backend,s.state,s.started,s.ended,s.identity,
        s.nodes AS stored_nodes,s.issues AS stored_issues,
        s.revision AS current_revision,s.provisional,s.metrics,n.device,n.valid FROM snapshots p JOIN sessions s ON s.id=p.sid
        LEFT JOIN nodes n ON n.sid=s.id AND n.id=1 WHERE p.id=?""", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) return null
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun number(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        val identity = JSONObject(text("identity"))
        val device = c.getColumnIndexOrThrow("device")
        identity.put("device", if (c.isNull(device) || number("valid") == 0L) "" else c.getBlob(device).hex())
            .put("filesystem", JSONObject(text("metrics")).optLong("rootFilesystemType"))
            .put("mount", JSONObject(text("metrics")).optLong("rootMountId"))
        JSONObject().put("id", id).put("sessionId", text("sid")).put("rootPath", text("root")).put("backend", text("backend"))
            .put("state", text("state")).put("startedAtMillis", number("started")).put("endedAtMillis", number("ended"))
            .put("createdAtMillis", number("created")).put("logicalBytes", number("logical")).put("allocatedBytes", number("allocated"))
            .put("lastLocalUpdateAtMillis", number("local_updated")).put("localUpdateCount", number("local_updates"))
            .put("estimatedStorageBytes", storage?.bytes(number("stored_nodes"), number("stored_issues")) ?: JSONObject.NULL)
            .put("complete", number("complete") == 1L && text("state") == "completed").put("retained", number("retained") == 1L)
            .put("revision", number("revision")).put("identity", identity)
            .put("available", number("revision") == number("current_revision") && number("provisional") == 0L && text("state") in setOf("completed", "partial"))
            .also { store.rescans.decorate(db, it, id) }
    }
    private fun compatibility(a: JSONObject, b: JSONObject): JSONArray {
        val reasons = JSONArray()
        if (!a.getBoolean("available") || !b.getBoolean("available")) reasons.put("快照索引已变化或尚未最终聚合")
        if (a.getString("rootPath") != b.getString("rootPath")) reasons.put("根路径不同")
        if (a.getString("backend") != b.getString("backend")) reasons.put("扫描模式不同（普通 / Root）")
        val x = a.getJSONObject("identity"); val y = b.getJSONObject("identity")
        if (!x.has("user") || !y.has("user") || x.optInt("user", -1) != y.optInt("user", -1)) reasons.put("Android 用户不同或未记录")
        if (x.optString("rules").isEmpty() || y.optString("rules").isEmpty() || x.optString("rules") != y.optString("rules")) reasons.put("统计规则不同或旧索引未记录规则，请重新扫描")
        if (x.optLong("filesystem") == 0L || x.optLong("filesystem") != y.optLong("filesystem")) reasons.put("文件系统不同或无法确认")
        val stableVolume = x.optString("volumeUuid").isNotEmpty() && y.optString("volumeUuid").isNotEmpty()
        if (stableVolume) {
            if (x.optString("volumeUuid") != y.optString("volumeUuid")) reasons.put("存储卷 UUID 不同")
        } else {
            if (x.optString("device").length != 16 || x.optString("device") != y.optString("device")) reasons.put("卷身份不同或无法确认")
            if (x.optLong("mount") == 0L || x.optLong("mount") != y.optLong("mount")) reasons.put("扫描挂载不同或无法确认")
            // st_dev can be reused after a reboot; UUID-less volumes need this restriction.
            if (x.optString("boot").isEmpty() || x.optString("boot") != y.optString("boot")) reasons.put("跨启动的卷身份无法确认，请在本次启动后重新扫描")
        }
        return reasons
    }
    private fun compare(db: SQLiteDatabase, q: JSONObject): JSONObject {
        val base = uuid(q, "baseId"); val target = uuid(q, "targetId"); require(base != target)
        val a = snapshot(db, base) ?: return response("not-found")
        val b = snapshot(db, target) ?: return response("not-found")
        val result = response("ok").put("base", a).put("target", b)
        val reasons = compatibility(a, b)
        if (reasons.length() != 0) return result.put("status", "incompatible").put("reasons", reasons)
        val sort = q.optString("sort", "logical"); require(sort in setOf("logical", "allocated", "path"))
        val filter = q.optString("filter", "all"); require(filter in setOf("all", "added", "deleted", "changed", "unknown", "growth"))
        val limit = q.optInt("limit", 50); require(limit in 1..100)
        val token = q.optString("cursor"); require(token.length <= 4096)
        val signature = "$base|${a.getLong("revision")}|$target|${b.getLong("revision")}"
        val cached = db.rawQuery("SELECT signature,ready FROM diff_cache WHERE id=1", null).use { if (it.moveToFirst()) it.getString(0) to (it.getInt(1) == 1) else null }
        if (cached?.first != signature || cached.second != true) {
            if (token.isNotEmpty()) return result.put("status", "stale-cursor")
            if (builder?.signature != signature || cached?.first != signature) {
                mutate(db) {
                    db.execSQL("DELETE FROM diff_rows"); db.execSQL("DELETE FROM diff_cache")
                    db.execSQL("INSERT INTO diff_cache VALUES(1,?,0)", arrayOf(signature)); response("ok")
                }
                builder = Builder(db, signature, base, target)
            }
            val work = builder!!
            val finished = try { mutate(db) {
                val done = work.step(db)
                if (done) db.execSQL("UPDATE diff_cache SET ready=1 WHERE id=1")
                response(if (done) "ok" else "building")
            }.getString("status") == "ok" } catch (error: Exception) { builder = null; throw error }
            if (!finished) return result.put("status", "building").put("processed", work.processed)
            builder = null
        }
        val query = "$signature|$sort|$filter|$limit"
        val cursor = if (token.isEmpty()) null else decode(token)
        if (cursor != null) {
            val previous = cursor.getString("query").split('|')
            val current = query.split('|')
            if (cursor.getInt("version") == 1 && previous.size == 7 && previous[0] == base && previous[2] == target &&
                previous.drop(4) == current.drop(4) && (previous[1] != current[1] || previous[3] != current[3])) {
                return result.put("status", "stale-cursor")
            }
            require(cursor.getInt("version") == 1 && cursor.getString("query") == query)
            require(cursor.getLong("rowId") >= 0)
        }
        val column = if (sort == "allocated") "allocated_delta" else "logical_delta"
        val conditions = mutableListOf("length(path)>0")
        val args = mutableListOf<String>()
        if (filter == "growth") conditions.add("new_kind=1 AND $column>0")
        else if (filter != "all") { conditions.add("status=?"); args.add(filter) }
        if (cursor != null) {
            val rowId = cursor.getLong("rowId")
            val path = db.rawQuery("SELECT hex(path) FROM diff_rows WHERE row_id=?", arrayOf(rowId.toString())).use {
                if (!it.moveToFirst()) return result.put("status", "stale-cursor")
                "X'${it.getString(0)}'"
            }
            if (sort == "path") conditions.add("path>$path") else {
                conditions.add("(COALESCE($column,-9223372036854775808),path)<(CAST(? AS INTEGER),$path)")
                args.add(cursor.getLong("delta").toString())
            }
        }
        args.add((limit + 1).toString())
        val order = if (sort == "path") "path" else "COALESCE($column,-9223372036854775808) DESC,path DESC"
        val rows = JSONArray(); var next: String? = null
        var bytes = 0; var lastRow = 0L; var lastDelta = 0L
        db.rawQuery("SELECT *,COALESCE($column,-9223372036854775808) AS sort_delta FROM diff_rows WHERE ${conditions.joinToString(" AND ")} ORDER BY $order LIMIT ?", args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                if (rows.length() == limit || (rows.length() > 0 && bytes + c.getBlob(0).size * 2 + 512 > 384000)) {
                    next = encode(JSONObject().put("version", 1).put("query", query).put("rowId", lastRow).put("delta", lastDelta))
                    break
                }
                val row = JSONObject().put("pathHex", c.getBlob(0).hex()).put("oldKind", kinds[c.getInt(1)]).put("newKind", kinds[c.getInt(2)])
                for ((index, key) in listOf(3 to "oldLogical", 4 to "newLogical", 5 to "oldAllocated", 6 to "newAllocated", 8 to "logicalDelta", 9 to "allocatedDelta")) row.put(key, if (c.isNull(index)) JSONObject.NULL else c.getLong(index))
                rows.put(row.put("status", c.getString(7)))
                bytes += row.toString().length; lastRow = c.getLong(10); lastDelta = c.getLong(11)
            }
        }
        val counts = JSONObject()
        db.rawQuery("SELECT status,COUNT(*) FROM diff_rows WHERE length(path)>0 GROUP BY status", null).use { while (it.moveToNext()) counts.put(it.getString(0), it.getLong(1)) }
        val complete = a.getBoolean("complete") && b.getBoolean("complete")
        return result.put("entries", rows).put("counts", counts).put("nextCursor", next).put("complete", complete)
            .put("logicalDelta", if (complete) b.getLong("logicalBytes") - a.getLong("logicalBytes") else JSONObject.NULL)
            .put("allocatedDelta", if (complete) b.getLong("allocatedBytes") - a.getLong("allocatedBytes") else JSONObject.NULL)
    }
    private data class Node(val path: ByteArray, val kind: Int, val logical: Long, val allocated: Long, val known: Boolean)
    private class Stream(private val db: SQLiteDatabase, private val sid: String) {
        private var page = emptyList<Node>(); private var position = 0; private var last: ByteArray? = null; private var exhausted = false
        fun peek(): Node? {
            if (position < page.size) return page[position]
            if (exhausted) return null
            val after = last?.let { " AND n.path>X'${it.hex()}'" } ?: ""
            page = db.rawQuery("""SELECT n.path,n.kind,a.logical,a.allocated,a.complete,n.valid,n.skip
                FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id WHERE n.sid=?$after ORDER BY n.path LIMIT 16""", arrayOf(sid)).use { c ->
                buildList { while (c.moveToNext()) add(Node(c.getBlob(0), c.getInt(1), c.getLong(2), c.getLong(3),
                    c.getInt(4) == 1 && c.getInt(5) == 1 && c.getInt(6) == 0)) }
            }
            position = 0
            exhausted = page.size < 16
            return page.firstOrNull()
        }
        fun advance() { last = page[position].path; position++ }
    }
    private class Builder(db: SQLiteDatabase, val signature: String, private val base: String, private val target: String) {
        private val left = Stream(db, base); private val right = Stream(db, target)
        private val coverage = LinkedHashMap<String, Boolean>(32, 0.75f, true)
        private var coverageBytes = 0
        var processed = 0L; private set
        private fun absenceKnown(db: SQLiteDatabase, sid: String, path: ByteArray): Boolean {
            if (path.isEmpty()) return false
            var end = path.lastIndexOf(47.toByte()).coerceAtLeast(0)
            val initialKey = "$sid:${path.copyOf(end).hex()}"
            coverage[initialKey]?.let { return it }
            var result = false
            while (true) {
                val prefix = path.copyOf(end).hex()
                val key = "$sid:$prefix"
                if (coverage.containsKey(key)) { result = coverage.getValue(key); break }
                val known = db.rawQuery("SELECT valid,skip,complete,kind,enumerated FROM nodes WHERE sid=? AND path=X'$prefix'", arrayOf(sid)).use {
                    if (!it.moveToFirst()) null else it.getInt(0) == 1 && it.getInt(1) == 0 && it.getInt(2) == 1 && (it.getInt(3) != 1 || it.getInt(4) == 1)
                }
                if (known != null) { result = known; break }
                if (end == 0) break
                end = path.copyOf(end).lastIndexOf(47.toByte()).coerceAtLeast(0)
            }
            coverage[initialKey] = result; coverageBytes += initialKey.length * 2
            while (coverage.size > 32 || coverageBytes > 1048576) {
                val iterator = coverage.entries.iterator(); coverageBytes -= iterator.next().key.length * 2; iterator.remove()
            }
            return result
        }
        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return (a[i].toInt() and 255) - (b[i].toInt() and 255)
            return a.size.compareTo(b.size)
        }
        fun step(db: SQLiteDatabase): Boolean {
            val started = android.os.SystemClock.elapsedRealtime()
            db.compileStatement("INSERT INTO diff_rows VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { insert ->
                repeat(512) {
                    val l = left.peek(); val r = right.peek()
                    if (l == null && r == null) return true
                    val order = if (l == null) 1 else if (r == null) -1 else compare(l.path, r.path)
                    val a = if (order <= 0) l else null; val b = if (order >= 0) r else null
                    val path = a?.path ?: b!!.path
                    // Raw lexicographic order is not preorder: "a-" sorts between
                    // "a" and "a/file". Prove absence via indexed exact ancestors.
                    val known = (a?.known ?: absenceKnown(db, base, path)) && (b?.known ?: absenceKnown(db, target, path))
                    val status = when {
                        !known -> "unknown"
                        a == null -> "added"
                        b == null -> "deleted"
                        a.kind != b.kind || a.logical != b.logical || a.allocated != b.allocated -> "changed"
                        else -> "unchanged"
                    }
                    if (status != "unchanged" || path.isEmpty()) {
                        insert.clearBindings(); insert.bindBlob(1, path); insert.bindLong(2, (a?.kind ?: 0).toLong()); insert.bindLong(3, (b?.kind ?: 0).toLong())
                        for ((index, value) in listOf(4 to a?.logical, 5 to b?.logical, 6 to a?.allocated, 7 to b?.allocated)) {
                            if (value != null) insert.bindLong(index, value) else if (known) insert.bindLong(index, 0) else insert.bindNull(index)
                        }
                        insert.bindString(8, status)
                        if (known) {
                            insert.bindLong(9, (b?.logical ?: 0) - (a?.logical ?: 0)); insert.bindLong(10, (b?.allocated ?: 0) - (a?.allocated ?: 0))
                        } else { insert.bindNull(9); insert.bindNull(10) }
                        insert.bindLong(11, processed)
                        insert.executeInsert()
                    }
                    if (a != null) left.advance()
                    if (b != null) right.advance()
                    processed++
                    if (android.os.SystemClock.elapsedRealtime() - started >= 200) return false
                }
            }
            return left.peek() == null && right.peek() == null
        }
    }
    private fun encode(value: JSONObject) = Base64.encodeToString(value.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    private fun decode(value: String) = JSONObject(String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
}
