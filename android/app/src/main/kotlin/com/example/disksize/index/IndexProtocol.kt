package com.example.disksize.index

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONObject

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
    val importWriteMicros: Long = 0, val performance: JSONObject? = null)

/** Strict bounded parser shared by ordinary and privileged producers. */
internal object IndexProtocol {
    const val VERSION = 4
    private const val HEADER_BYTES = 36
    private const val MAX_PAYLOAD = 65536

    private class Payload(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        fun resetTo(length: Int) {
            pos = 0
            count = length
        }
    }

    fun read(input: InputStream, id: String, uid: Int, pid: Int, writer: IndexConsumer,
        reuseBuffers: Boolean = true,
        progress: (Long, Long, Long) -> Unit): StreamSummary {
        val start = System.nanoTime()
        val expected = uuidBytes(id); val stream = DataInputStream(input)
        val expectedUuid = ByteBuffer.wrap(expected)
        val most = expectedUuid.long; val least = expectedUuid.long
        val header = if (reuseBuffers) ByteBuffer.allocate(HEADER_BYTES) else null
        val payload = if (reuseBuffers) ByteArray(MAX_PAYLOAD) else null
        val window = payload?.let(::Payload)
        val reader = window?.let(::DataInputStream)
        var sequence = 0L; var count = 0L; var issues = 0L; var identity = false
        var summary: StreamSummary? = null
        var producer: JSONObject? = null
        var volumeSeen = false
        var bytes = 0L; var batches = 0; var maxBatch = 0
        var receiveNanos = 0L; var dispatchNanos = 0L; var progressNanos = 0L
        val nodeKinds = LongArray(kinds.size)
        while (true) {
            val beforeRead = System.nanoTime()
            val type: Int
            val length: Int
            val body: DataInputStream
            val afterRead: Long
            if (header != null && payload != null && window != null && reader != null) {
                stream.readFully(header.array())
                header.rewind()
                require(header.int == 0x44535a34 && header.short.toInt() == VERSION) { "Bad stream magic/version" }
                type = header.short.toInt() and 0xffff; length = header.int
                require(length in 0..MAX_PAYLOAD) { "Frame too large" }
                require(header.long == most && header.long == least) { "Session mismatch" }
                require(header.long == sequence++) { "Sequence mismatch" }
                stream.readFully(payload, 0, length)
                afterRead = System.nanoTime()
                window.resetTo(length)
                body = reader
            } else {
                require(stream.readInt() == 0x44535a34 && stream.readUnsignedShort() == VERSION) { "Bad stream magic/version" }
                type = stream.readUnsignedShort(); length = stream.readInt()
                require(length in 0..MAX_PAYLOAD) { "Frame too large" }
                require(ByteArray(16).also { stream.readFully(it) }.contentEquals(expected)) { "Session mismatch" }
                require(stream.readLong() == sequence++) { "Sequence mismatch" }
                val bytes = ByteArray(length).also { stream.readFully(it) }
                afterRead = System.nanoTime()
                body = DataInputStream(ByteArrayInputStream(bytes))
            }
            receiveNanos += afterRead - beforeRead
            require(identity || type == 1)
            require(if (producer != null) type == 6 else if (summary != null) type == 9 else type != 9 && type != 6)
            when (type) {
                1 -> {
                    require(!identity && sequence == 1L)
                    require(body.readInt() == uid && body.readInt() == uid && body.readInt() == pid) { "Scanner identity mismatch" }
                    identity = true
                }
                2 -> {
                    val nodes = body.positive(); val logical = body.positive(); val totalIssues = body.positive(); body.positive()
                    require(nodes == count && totalIssues == issues)
                    writer.flush(IndexStore.FlushReason.PROGRESS)
                    val beforeProgress = System.nanoTime()
                    progress(nodes, logical, totalIssues)
                    progressNanos += System.nanoTime() - beforeProgress
                }
                3 -> {
                    val size = body.readInt(); require(size in 1..256)
                    batches++; maxBatch = maxOf(maxBatch, size)
                    repeat(size) {
                        val nodeId = body.positive(); val parent = body.positive()
                        val name = body.blob(255); val link = body.blob(4096)
                        require(nodeId == count + 1 && nodeId <= 2_000_000 && parent < nodeId)
                        require(if (nodeId == 1L) parent == 0L && name.isEmpty() else parent > 0 && name.isNotEmpty())
                        require(name.none { it == 0.toByte() || it == 47.toByte() } &&
                            !(name.size == 1 && name[0] == 46.toByte()) &&
                            !(name.size == 2 && name[0] == 46.toByte() && name[1] == 46.toByte()))
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
                        nodeKinds[kind]++
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
                    writer.flush(IndexStore.FlushReason.SUMMARY)
                }
                8 -> {
                    require(!volumeSeen); volumeSeen = true
                    val total = body.positive(); val free = body.positive(); val available = body.positive(); val captured = body.positive()
                    require(free <= total && available <= free && captured > 0)
                    writer.volume(total, free, available, captured)
                }
                9 -> {
                    require(length == 40)
                    val elapsed = body.positive(); val writes = body.positive(); val polls = body.positive()
                    val frames = body.positive(); val wireBytes = body.positive()
                    require(polls <= writes && writes <= elapsed && checkNotNull(summary).elapsed <= elapsed)
                    require(frames == sequence - 1 && wireBytes == bytes) { "Producer frame counts mismatch" }
                    producer = JSONObject().put("elapsedMicros", elapsed).put("pipeWriteMicros", writes)
                        .put("pipePollMicros", polls).put("frames", frames).put("wireBytes", wireBytes)
                        .put("nodeBatches", batches).put("maxNodesPerBatch", maxBatch)
                        .put("nodesByKind", JSONObject().apply { kinds.forEachIndexed { i, kind -> put(kind, nodeKinds[i]) } })
                }
                6 -> {
                    require(length == 0 && stream.read() == -1) { "Missing end/EOF" }
                    val end = System.nanoTime()
                    receiveNanos += end - afterRead
                    val consumer = writer.measurements()
                        .put("elapsedMicros", (end - start) / 1000)
                        .put("receiveMicros", receiveNanos / 1000)
                        .put("decodeMicros", (dispatchNanos - writer.measuredImportNanos - progressNanos) / 1000)
                        .put("progressMicros", progressNanos / 1000)
                    return checkNotNull(summary).copy(
                        importWriteMicros = writer.writeNanos / 1000,
                        performance = JSONObject().put("version", 1).put("producer", producer).put("consumer", consumer),
                    )
                }
                else -> error("Unknown record")
            }
            require(body.available() == 0) { "Trailing payload" }
            bytes += HEADER_BYTES + length
            dispatchNanos += System.nanoTime() - afterRead
        }
    }
    private fun DataInputStream.positive() = readLong().also { require(it >= 0) }
    private fun DataInputStream.flag() = readInt().also { require(it in 0..1) } == 1
    private fun DataInputStream.blob(max: Int): ByteArray {
        val size = readInt(); require(size in 0..max && size <= available())
        return ByteArray(size).also { readFully(it) }
    }
}
