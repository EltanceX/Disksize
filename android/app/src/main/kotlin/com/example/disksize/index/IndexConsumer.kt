package com.example.disksize.index

import org.json.JSONObject

/** Called synchronously after wire validation. The caller owns backpressure. */
internal interface IndexConsumer {
    val writeNanos: Long get() = 0
    val measuredImportNanos: Long get() = 0
    fun node(n: IndexNode)
    fun directory(node: Long, enumerated: Boolean, complete: Boolean, skip: Int)
    fun issue(node: Long, code: Int, errno: Int, op: String)
    fun volume(total: Long, free: Long, available: Long, captured: Long)
    fun flush(reason: IndexStore.FlushReason)
    fun measurements(): JSONObject = JSONObject()
}
