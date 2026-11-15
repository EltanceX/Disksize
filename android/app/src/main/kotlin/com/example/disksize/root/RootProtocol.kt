package com.example.disksize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

/** v1, big endian; a maximum 64 KiB frame and bounded 64-entry UI preview.
 * Final node batches follow native aggregation. No tree is retained in Dart or
 * the coordinator; phase 5 can replace this consumer with a transactional index.
 */
internal object RootProtocol {
    private val kinds = arrayOf("unknown", "directory", "regular", "symlink", "special")
    private val skips = arrayOf("none", "mount-boundary", "pseudo-filesystem", "directory-alias", "resource-limit",
        "cancelled", "changed-during-scan", "unknown-mount")
    private val errors = arrayOf("invalid-configuration", "permission-denied", "not-found", "io", "cancelled",
        "unsupported", "changed-during-scan", "resource-limit", "consumer-failure")
    private val states = mapOf(4 to "cancelled", 5 to "completed", 6 to "partial", 7 to "failed", 8 to "interrupted")

    fun read(input: InputStream, sessionId: String, expectedPid: Int, progress: (Long, Long, Long) -> Unit): JSONObject {
        val uuid = UUID.fromString(sessionId)
        val session = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        val stream = DataInputStream(input)
        val entries = JSONArray(); val issues = JSONArray()
        val previewPaths = mutableMapOf<Long, ByteArray>()
        var sequence = 0L; var nodeCount = 0L; var issueCount = 0L
        var pathBudget = 0; var issueBudget = 0; var truncated = false
        var identity = false; var summary: JSONObject? = null
        try {
            while (true) {
                require(stream.readInt() == 0x44535a34) { "Bad magic" }
                require(stream.readUnsignedShort() == 1) { "Unsupported version" }
                val type = stream.readUnsignedShort()
                val length = stream.readInt()
                require(length in 0..65536) { "Invalid frame length" }
                val frameSession = ByteArray(16); stream.readFully(frameSession)
                require(frameSession.contentEquals(session)) { "Session mismatch" }
                require(stream.readLong() == sequence++) { "Sequence mismatch" }
                val payload = ByteArray(length); stream.readFully(payload)
                val body = DataInputStream(ByteArrayInputStream(payload))
                require(identity || type == 1) { "Identity missing" }
                require(summary == null || type == 6) { "Record after summary" }
                when (type) {
                    1 -> {
                        require(!identity && sequence == 1L)
                        require(body.readInt() == 0 && body.readInt() == 0 && body.readInt() == expectedPid) { "Root identity mismatch" }
                        identity = true
                    }
                    2 -> {
                        require(nodeCount == 0L && issueCount == 0L)
                        val nodes = body.nonnegative(); val logical = body.nonnegative(); val count = body.nonnegative()
                        body.nonnegative()
                        require(nodes <= 100000)
                        progress(nodes, logical, count)
                    }
                    3 -> {
                        require(issueCount == 0L)
                        val count = body.readInt(); require(count in 1..1638)
                        repeat(count) {
                            val id = body.nonnegative(); val parent = body.nonnegative(); val name = body.blob(255)
                            require(id == nodeCount + 1 && id <= 100000 && parent < id)
                            require(if (id == 1L) parent == 0L && name.isEmpty() else parent > 0 && name.isNotEmpty())
                            require(name.none { it == 0.toByte() || it == '/'.code.toByte() })
                            val kind = body.readUnsignedByte(); require(kind in kinds.indices)
                            val complete = body.flag(); val skip = body.readUnsignedByte(); require(skip in skips.indices)
                            require(body.readUnsignedByte() == 0)
                            val logical = body.nonnegative(); val allocated = body.nonnegative()
                            nodeCount++
                            if (id <= 64) {
                                val ancestor = if (parent == 0L) byteArrayOf() else previewPaths[parent]
                                val path = ancestor?.let { if (it.isEmpty()) name else it + byteArrayOf(47) + name }
                                if (path != null && path.size <= 4096 && pathBudget + path.size * 2 <= 65536) {
                                    previewPaths[id] = path; pathBudget += path.size * 2
                                    entries.put(JSONObject().put("pathHex", path.hex()).put("kind", kinds[kind])
                                        .put("logicalBytes", logical).put("allocatedBytes", allocated)
                                        .put("complete", complete).put("skipped", skips[skip]))
                                } else truncated = true
                            } else truncated = true
                        }
                    }
                    4 -> {
                        require(++issueCount <= 64)
                        val node = body.nonnegative(); require(node <= nodeCount)
                        val code = body.readInt(); require(code in errors.indices)
                        val systemError = body.readInt()
                        val operation = body.blob(128)
                        require(operation.all { (it.toInt() and 255) in 32..126 })
                        val pathKnown = body.readInt(); require(pathKnown in 0..1)
                        val path = body.blob(4096)
                        if (pathKnown == 1 && issueBudget + path.size * 2 <= 65536) {
                            issueBudget += path.size * 2
                            issues.put(JSONObject().put("pathHex", path.hex()).put("code", errors[code])
                                .put("operation", operation.toString(Charsets.US_ASCII)).put("systemError", systemError))
                        } else truncated = true
                    }
                    5 -> {
                        val state = states[body.readInt()]; require(state != null)
                        val complete = body.readInt(); require(complete in 0..1)
                        require(complete == 0 || state == "completed")
                        require(body.nonnegative() == nodeCount) { "Node count mismatch" }
                        val logical = body.nonnegative(); val allocated = body.nonnegative(); val totalIssues = body.nonnegative()
                        val dropped = body.nonnegative(); require(totalIssues >= issueCount && dropped == totalIssues - issueCount)
                        val peak = body.nonnegative(); val finish = body.nonnegative()
                        val mount = body.nonnegative(); val fs = body.nonnegative()
                        require(body.readInt().toLong() == issueCount) { "Issue count mismatch" }
                        truncated = truncated || dropped > 0
                        summary = JSONObject().put("state", state).put("complete", complete == 1)
                            .put("nodeCount", nodeCount).put("logicalBytes", logical).put("allocatedBytes", allocated)
                            .put("issueCount", totalIssues).put("previewTruncated", truncated)
                            .put("peakOpenFds", peak).put("openFdsAtFinish", finish)
                            .put("rootMountId", mount).put("rootFilesystemType", fs)
                            .put("backend", "root").put("scannerUid", 0).put("scannerPid", expectedPid)
                            .put("entries", entries).put("issues", issues)
                    }
                    6 -> {
                        require(summary != null && length == 0) { "Completion missing" }
                        require(stream.read() == -1) { "Data after completion" }
                        return summary
                    }
                    else -> error("Unknown frame type")
                }
                require(body.available() == 0) { "Unexpected payload bytes" }
            }
        } catch (error: Exception) { throw RootFailure("root-pipe-or-protocol-failure", error) }
    }

    private fun DataInputStream.nonnegative(): Long = readLong().also { require(it >= 0) }
    private fun DataInputStream.blob(max: Int): ByteArray {
        val length = readInt(); require(length in 0..max && length <= available())
        return ByteArray(length).also { readFully(it) }
    }
    private fun DataInputStream.flag(): Boolean = readUnsignedByte().also { require(it in 0..1) } == 1
    private fun ByteArray.hex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) { for (byte in this@hex) {
            val value = byte.toInt() and 255
            append(digits[value shr 4]); append(digits[value and 15])
        } }
    }
}
