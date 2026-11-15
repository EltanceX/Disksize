package com.example.disksize.index

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Pages contain at most 100 rows. No unbounded page or path cache. */
internal class IndexQueries(private val store: IndexStore) {
    fun query(request: String): String {
        return try { execute(JSONObject(request)).toString() }
        catch (_: IllegalArgumentException) { JSONObject().put("status", "invalid-query").toString() }
        catch (_: org.json.JSONException) { JSONObject().put("status", "invalid-query").toString() }
    }
    private fun execute(q: JSONObject): JSONObject {
        val id = q.getString("sessionId"); require(id.length == 36)
        val kind = q.getString("kind"); require(kind in setOf("session", "children", "large-files", "detail", "issues"))
        val parent = q.optLong("parentId"); val node = q.optLong("nodeId")
        val sort = q.optString("sort", "logical"); require(sort in setOf("logical", "allocated", "name"))
        val limit = q.optInt("limit", 100); require(limit in 1..100 && parent >= 0 && node >= 0)
        require(kind != "children" || parent > 0); require(kind != "detail" || node > 0)
        require(kind != "large-files" || sort != "name")
        val token = q.optString("cursor"); require(token.length <= 4096)
        val db = store.readableDatabase
        val meta = metadata(db, id) ?: return JSONObject().put("status", "not-found")
        val revision = meta.getLong("revision")
        val signature = "$id|$kind|$parent|$node|$sort|$limit"
        val cursor = if (token.isEmpty()) null else JSONObject(String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)).also {
            require(it.getInt("version") == 1 && it.getString("query") == signature)
        }
        if (cursor != null && cursor.getLong("revision") != revision) return meta.put("status", "stale-cursor")
        val result = meta.put("status", "ok").put("entries", JSONArray()).put("issues", JSONArray())
        if (kind == "detail") {
            db.rawQuery("""SELECT n.*,a.logical,a.allocated AS total_allocated,a.complete AS subtree_complete,a.owner
                FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id WHERE n.sid=? AND n.id=?""", arrayOf(id, node.toString())).use {
                if (!it.moveToFirst()) result.put("status", "not-found") else result.put("detail", detail(it))
            }
        } else if (kind == "issues") {
            val after = cursor?.getLong("lastId") ?: 0L; require(after >= 0)
            val filter = if (node == 0L) "" else " AND node=?"
            val args = mutableListOf(id, after.toString()); if (node != 0L) args.add(node.toString()); args.add((limit + 1).toString())
            val rows = result.getJSONArray("issues"); var last = 0L
            db.rawQuery("SELECT id,node,code,errno,operation FROM issues WHERE sid=? AND id>?$filter ORDER BY id LIMIT ?", args.toTypedArray()).use {
                while (it.moveToNext()) {
                    if (rows.length() == limit) { result.put("nextCursor", encode(signature, revision, last, "")); break }
                    last = it.getLong(0)
                    rows.put(JSONObject().put("id", last).put("nodeId", it.getLong(1)).put("code", errors[it.getInt(2)])
                        .put("systemError", it.getInt(3)).put("operation", it.getString(4)))
                }
            }
        } else if (kind != "session") {
            val column = if (sort == "name") "name_key" else sort
            val ascending = sort == "name"; val comparison = if (ascending) ">" else "<"
            val order = if (ascending) "ASC" else "DESC"
            val filter = if (kind == "children") "a.parent=?" else "a.kind=2"
            val args = mutableListOf(id); if (kind == "children") args.add(parent.toString())
            val continuation = if (cursor == null) "" else {
                val lastId = cursor.getLong("lastId"); require(lastId > 0)
                val key = cursor.getString("key")
                if (ascending) require(key.length <= 510 && key.matches(Regex("[0-9a-f]*")))
                else require(key.toLong() >= 0)
                args.add(key); args.add(lastId.toString()); " AND (a.$column,a.id)$comparison(?,?)"
            }
            args.add((limit + 1).toString())
            val rows = result.getJSONArray("entries"); var lastId = 0L; var lastKey = ""
            db.rawQuery("""SELECT a.id,a.parent,n.name,a.kind,a.logical,a.allocated,a.complete,a.owner,a.$column
                FROM aggregates a JOIN nodes n ON n.sid=a.sid AND n.id=a.id
                WHERE a.sid=? AND $filter$continuation ORDER BY a.$column $order,a.id $order LIMIT ?""", args.toTypedArray()).use {
                while (it.moveToNext()) {
                    if (rows.length() == limit) { result.put("nextCursor", encode(signature, revision, lastId, lastKey)); break }
                    lastId = it.getLong(0); lastKey = it.getString(8)
                    rows.put(JSONObject().put("id", lastId).put("parentId", it.getLong(1)).put("nameHex", it.getBlob(2).hex())
                        .put("kind", kinds[it.getInt(3)]).put("logicalBytes", it.getLong(4)).put("allocatedBytes", it.getLong(5))
                        .put("complete", it.getInt(6) == 1).put("ownerId", it.getLong(7)))
                }
            }
        }
        // All indexed writes advance revision in the same transaction. This
        // double-read accepts only a stable interval, also on Android 29 where
        // SQLiteDatabase has no read-only transaction API.
        if (metadata(db, id)?.getLong("revision") != revision) return meta.put("status", "stale-cursor").put("entries", JSONArray()).put("issues", JSONArray()).also { it.remove("detail"); it.remove("nextCursor") }
        return result
    }
    private fun metadata(db: SQLiteDatabase, id: String): JSONObject? = db.rawQuery(
        "SELECT revision,provisional,state,phase,nodes,issues,metrics FROM sessions WHERE id=?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else JSONObject().put("revision", it.getLong(0)).put("provisional", it.getInt(1) == 1)
            .put("state", it.getString(2)).put("phase", it.getString(3)).put("nodeCount", it.getLong(4)).put("issueCount", it.getLong(5))
            .put("schemaVersion", IndexStore.SCHEMA_VERSION).put("metrics", JSONObject(it.getString(6)))
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
            .put("skipped", skips[long("skip").toInt()])
    }
    private fun encode(query: String, revision: Long, id: Long, key: String): String = Base64.encodeToString(
        JSONObject().put("version", 1).put("query", query).put("revision", revision).put("lastId", id).put("key", key)
            .toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
}
