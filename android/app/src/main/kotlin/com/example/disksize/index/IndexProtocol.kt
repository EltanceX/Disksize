package com.example.disksize.index

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

internal fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"; val out = CharArray(size * 2)
    forEachIndexed { i, b -> val v = b.toInt() and 255; out[i * 2] = digits[v shr 4]; out[i * 2 + 1] = digits[v and 15] }
    return String(out)
}
internal fun uuidBytes(id: String): ByteArray = UUID.fromString(id).let {
    ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
}
internal val kinds = arrayOf("unknown", "directory", "regular", "symlink", "special")
internal val skips = arrayOf("none", "mount-boundary", "pseudo-filesystem", "directory-alias", "resource-limit",
    "cancelled", "changed-during-scan", "unknown-mount")
internal val errors = arrayOf("invalid-configuration", "permission-denied", "not-found", "io", "cancelled",
    "unsupported", "changed-during-scan", "resource-limit", "consumer-failure")
internal data class IndexNode(val id: Long, val parent: Long, val name: ByteArray, val link: ByteArray,
    val kind: Int, val depth: Int, val mode: Long, val uid: Long, val gid: Long,
    val device: ByteArray, val inode: ByteArray, val mount: Long, val links: Long,
    val size: Long, val allocated: Long, val seconds: Long, val nanos: Long,
    val valid: Boolean, val complete: Boolean, val skip: Int)
internal data class StreamSummary(val state: String, val nodes: Long, val issues: Long,
    val elapsed: Long, val peakFds: Long, val finishFds: Long, val mount: Long, val filesystem: Long,
    val importWriteMicros: Long = 0)

/** Strict bounded parser shared by ordinary and privileged producers. */
internal object IndexProtocol {
    fun read(input: InputStream, id: String, uid: Int, pid: Int, writer: IndexStore.Import,
        progress: (Long, Long, Long) -> Unit): StreamSummary {
        val expected = uuidBytes(id); val stream = DataInputStream(input)
        var sequence = 0L; var count = 0L; var issues = 0L; var identity = false
        var summary: StreamSummary? = null
        var volumeSeen = false
        while (true) {
            require(stream.readInt() == 0x44535a34 && stream.readUnsignedShort() == 3) { "Bad stream magic/version" }
            val type = stream.readUnsignedShort(); val length = stream.readInt()
            require(length in 0..65536) { "Frame too large" }
            require(ByteArray(16).also { stream.readFully(it) }.contentEquals(expected)) { "Session mismatch" }
            require(stream.readLong() == sequence++) { "Sequence mismatch" }
            val body = DataInputStream(ByteArrayInputStream(ByteArray(length).also { stream.readFully(it) }))
            require(identity || type == 1); require(summary == null || type == 6)
            when (type) {
                1 -> {
                    require(!identity && sequence == 1L)
                    require(body.readInt() == uid && body.readInt() == uid && body.readInt() == pid) { "Scanner identity mismatch" }
                    identity = true
                }
                2 -> {
                    val nodes = body.positive(); val logical = body.positive(); val totalIssues = body.positive(); body.positive()
                    require(nodes == count && totalIssues == issues)
                    writer.flush(); progress(nodes, logical, totalIssues)
                }
                3 -> {
                    val size = body.readInt(); require(size in 1..256)
                    repeat(size) {
                        val nodeId = body.positive(); val parent = body.positive()
                        val name = body.blob(255); val link = body.blob(4096)
                        require(nodeId == count + 1 && nodeId <= 2_000_000 && parent < nodeId)
                        require(if (nodeId == 1L) parent == 0L && name.isEmpty() else parent > 0 && name.isNotEmpty())
                        require(name.none { it == 0.toByte() || it == 47.toByte() } && !name.contentEquals(byteArrayOf(46)) && !name.contentEquals(byteArrayOf(46, 46)))
                        val kind = body.readInt(); val depth = body.readInt()
                        require(kind in kinds.indices && depth in 0..512)
                        val mode = body.readInt().toLong() and 0xffffffffL
                        val user = body.readInt().toLong() and 0xffffffffL; val group = body.readInt().toLong() and 0xffffffffL
                        val device = ByteArray(8).also { body.readFully(it) }; val inode = ByteArray(8).also { body.readFully(it) }
                        val mount = body.positive(); val links = body.positive(); val bytes = body.positive(); val allocated = body.positive()
                        val seconds = body.readLong(); val nanos = body.positive(); require(nanos < 1_000_000_000)
                        val valid = body.flag(); val complete = body.flag(); val skip = body.readInt(); require(skip in skips.indices)
                        writer.node(IndexNode(nodeId, parent, name, link, kind, depth, mode, user, group, device, inode,
                            mount, links, bytes, allocated, seconds, nanos, valid, complete, skip)); count++
                    }
                }
                4 -> {
                    val node = body.positive(); require(node <= count)
                    val code = body.readInt(); require(code in errors.indices)
                    val errno = body.readInt(); val operation = body.blob(128)
                    require(operation.all { (it.toInt() and 255) in 32..126 })
                    writer.issue(node, code, errno, operation.toString(Charsets.US_ASCII)); issues++
                }
                7 -> {
                    val node = body.positive(); require(node in 1..count)
                    val enumerated = body.flag(); val complete = body.flag(); val skip = body.readInt(); require(skip in skips.indices)
                    writer.directory(node, enumerated, complete, skip)
                }
                5 -> {
                    val state = mapOf(4 to "cancelled", 5 to "completed", 6 to "partial", 7 to "failed")[body.readInt()]
                    require(state != null && body.positive() == count && body.positive() == issues)
                    summary = StreamSummary(state, count, issues, body.positive(), body.positive(), body.positive(), body.positive(), body.positive())
                    require(summary.finishFds == 0L)
                    writer.flush()
                }
                8 -> {
                    require(!volumeSeen); volumeSeen = true
                    val total = body.positive(); val free = body.positive(); val available = body.positive(); val captured = body.positive()
                    require(free <= total && available <= free && captured > 0)
                    writer.volume(total, free, available, captured)
                }
                6 -> {
                    require(summary != null && length == 0 && stream.read() == -1) { "Missing end/EOF" }
                    return summary
                }
                else -> error("Unknown record")
            }
            require(body.available() == 0) { "Trailing payload" }
        }
    }
    private fun DataInputStream.positive() = readLong().also { require(it >= 0) }
    private fun DataInputStream.flag() = readInt().also { require(it in 0..1) } == 1
    private fun DataInputStream.blob(max: Int): ByteArray {
        val size = readInt(); require(size in 0..max && size <= available())
        return ByteArray(size).also { readFully(it) }
    }
}
