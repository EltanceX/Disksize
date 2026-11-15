package com.example.disksize.root

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID

class RootProtocolTest {
    private val id = "12345678-1234-4321-9123-123456789abc"
    private val uuid = UUID.fromString(id)
    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()
    private fun DataOutputStream.blob(value: ByteArray) { writeInt(value.size); write(value) }
    private fun frame(type: Int, sequence: Long, body: ByteArray = byteArrayOf()): ByteArray = bytes {
        writeInt(0x44535a34); writeShort(1); writeShort(type); writeInt(body.size)
        writeLong(uuid.mostSignificantBits); writeLong(uuid.leastSignificantBits); writeLong(sequence); write(body)
    }
    private fun fixture(): ByteArray {
        val identity = frame(1, 0, bytes { writeInt(0); writeInt(0); writeInt(123) })
        val progress = frame(2, 1, bytes { writeLong(2); writeLong(7); writeLong(0); writeLong(100) })
        val nodes = frame(3, 2, bytes {
            writeInt(2)
            writeLong(1); writeLong(0); blob(byteArrayOf()); writeByte(1); writeByte(1); writeByte(0); writeByte(0)
            writeLong(7); writeLong(4096)
            writeLong(2); writeLong(1); blob(byteArrayOf(0xff.toByte(), 10, 32, 0xe4.toByte(), 0xb8.toByte(), 0xad.toByte()))
            writeByte(2); writeByte(1); writeByte(0); writeByte(0); writeLong(7); writeLong(0)
        })
        val summary = frame(5, 3, bytes {
            writeInt(5); writeInt(1); writeLong(2); writeLong(7); writeLong(4096); writeLong(0)
            writeLong(0); writeLong(4); writeLong(0); writeLong(42); writeLong(0xf2f52010); writeInt(0)
        })
        return identity + progress + nodes + summary + frame(6, 4)
    }
    private fun read(value: ByteArray) = RootProtocol.read(ByteArrayInputStream(value), id, 123) { _, _, _ -> }

    @Test fun `valid stream retains invalid UTF8 newline space and unicode bytes`() {
        val value = read(fixture())
        assertEquals("completed", value.getString("state"))
        assertEquals(2, value.getLong("nodeCount"))
        assertEquals("ff0a20e4b8ad", value.getJSONArray("entries").getJSONObject(1).getString("pathHex"))
        assertEquals(0, value.getInt("scannerUid"))
    }
    @Test fun `short header payload or missing completion never completes`() {
        val value = fixture()
        for (size in listOf(0, 1, 35, 40, 47, value.size - 1, value.size - 36)) {
            assertThrows(RootFailure::class.java) { read(value.copyOf(size)) }
        }
    }
    @Test fun `header fields and trailing data are rejected`() {
        for ((offset, replacement) in listOf(0 to 0, 5 to 2, 7 to 99, 12 to 0, 35 to 1, 39 to 1)) {
            val changed = fixture(); changed[offset] = replacement.toByte()
            assertThrows("offset $offset", RootFailure::class.java) { read(changed) }
        }
        assertThrows(RootFailure::class.java) { read(fixture() + byteArrayOf(0)) }
        for (length in listOf(-1, 65537, Int.MAX_VALUE)) {
            val changed = fixture(); ByteBuffer.wrap(changed).putInt(8, length)
            assertThrows(RootFailure::class.java) { read(changed) }
        }
    }
    @Test fun `identity sequence counts parent links enums and blob sizes are checked`() {
        // Frame offsets: identity=0 (48 bytes), progress=48 (68), nodes=116.
        for ((offset, replacement) in listOf(48 + 35 to 9, 116 + 36 + 3 to 3,
            116 + 36 + 4 + 7 to 2, 116 + 36 + 4 + 15 to 1,
            116 + 36 + 4 + 19 to 255, 116 + 36 + 4 + 20 to 9)) {
            val changed = fixture(); changed[offset] = replacement.toByte()
            assertThrows("offset $offset", RootFailure::class.java) { read(changed) }
        }
        assertThrows(RootFailure::class.java) {
            RootProtocol.read(ByteArrayInputStream(fixture()), id, 999) { _, _, _ -> }
        }
    }
}
