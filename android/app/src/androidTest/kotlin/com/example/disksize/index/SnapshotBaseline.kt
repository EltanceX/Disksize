package com.example.disksize.index

import android.database.Cursor

/** Small controlled fixtures only: production queries must remain paginated. */
internal data class SnapshotBaseline(
    val summary: Map<String, String?>,
    val nodes: List<Map<String, String?>>,
    val issues: List<Map<String, String?>>,
) {
    fun row(path: ByteArray): Map<String, String?> = nodes.single { it["path"] == path.hex() }
    fun row(path: String) = row(path.toByteArray(Charsets.UTF_8))

    companion object {
        fun capture(store: IndexStore, session: String): SnapshotBaseline {
            val db = store.readableDatabase
            val summary = db.rawQuery("""SELECT s.state,s.provisional,s.nodes,s.issues,s.backend,
                p.logical,p.allocated,p.complete,s.revision AS session_revision,p.revision AS snapshot_revision
                FROM sessions s JOIN snapshots p ON p.sid=s.id WHERE s.id=?""", arrayOf(session)).use {
                check(it.moveToFirst()) { "Expected a finalized snapshot" }
                check(it.getLong(it.getColumnIndexOrThrow("session_revision")) == it.getLong(it.getColumnIndexOrThrow("snapshot_revision")))
                values(it, setOf("session_revision", "snapshot_revision"))
            }
            val nodes = db.rawQuery("""SELECT n.*,p.path AS parent_path,o.path AS owner_path,
                a.parent AS aggregate_parent,a.depth AS aggregate_depth,a.kind AS aggregate_kind,
                a.name_key AS aggregate_name_key,a.own_logical,a.own_allocated,
                a.logical AS total_logical,a.allocated AS total_allocated,a.complete AS subtree_complete
                FROM nodes n JOIN aggregates a ON a.sid=n.sid AND a.id=n.id
                LEFT JOIN nodes p ON p.sid=n.sid AND p.id=n.parent
                LEFT JOIN nodes o ON o.sid=a.sid AND o.id=a.owner
                WHERE n.sid=? ORDER BY n.path""", arrayOf(session)).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                        check(number("parent") == number("aggregate_parent"))
                        check(number("depth") == number("aggregate_depth"))
                        check(number("kind") == number("aggregate_kind"))
                        check(!cursor.isNull(cursor.getColumnIndexOrThrow("owner_path"))) { "Unresolved hardlink owner" }
                        check(number("parent") == 0L || !cursor.isNull(cursor.getColumnIndexOrThrow("parent_path")))
                        add(values(cursor, setOf("sid", "id", "parent", "aggregate_parent")))
                    }
                }
            }
            check(nodes.size.toLong() == summary.getValue("nodes")!!.toLong())
            val aggregateCount = db.rawQuery("SELECT COUNT(*) FROM aggregates WHERE sid=?", arrayOf(session)).use {
                it.moveToFirst(); it.getLong(0)
            }
            check(aggregateCount == nodes.size.toLong())
            val issues = db.rawQuery("""SELECT n.path,i.code,i.errno,i.operation FROM issues i
                LEFT JOIN nodes n ON n.sid=i.sid AND n.id=i.node WHERE i.sid=?
                ORDER BY n.path,i.code,i.errno,i.operation""", arrayOf(session)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(values(cursor)) }
            }
            check(issues.size.toLong() == summary.getValue("issues")!!.toLong())
            return SnapshotBaseline(summary, nodes, issues)
        }

        private fun values(cursor: Cursor, excluded: Set<String> = emptySet()): Map<String, String?> = buildMap {
            for (index in 0 until cursor.columnCount) {
                val key = cursor.getColumnName(index)
                if (key in excluded) continue
                put(key, when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).hex()
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    else -> error("Unexpected baseline column type: $key")
                })
            }
        }
    }
}
