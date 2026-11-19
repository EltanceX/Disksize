package com.example.disksize.scan

import com.example.disksize.scan.SnapshotRescanContract.Cancellation
import com.example.disksize.scan.SnapshotRescanContract.Error
import com.example.disksize.scan.SnapshotRescanContract.LiveTarget
import com.example.disksize.scan.SnapshotRescanContract.Request
import com.example.disksize.scan.SnapshotRescanContract.State
import com.example.disksize.scan.SnapshotRescanContract.Target
import org.junit.Assert.*
import org.junit.Test

class SnapshotRescanContractTest {
    private val request = Request("12345678-1234-4321-9123-123456789abc", 1, 7)
    private val target = Target(7)

    @Test fun acceptsAvailableDirectoryIncludingSnapshotRoot() {
        assertNull(SnapshotRescanContract.admissionError(request, target, false))
        assertNull(SnapshotRescanContract.admissionError(request.copy(nodeId = 19), target, false))
        // Availability can include a globally Partial snapshot; only the new subtree must be complete.
        assertNull(SnapshotRescanContract.admissionError(request, target.copy(available = true), false))
    }

    @Test fun validatesIdentifiersBeforeAnyLookup() {
        for (id in listOf("", "1-1-1-1-1", "../snapshot", request.snapshotId + "\n")) {
            assertEquals(Error.INVALID_REQUEST, SnapshotRescanContract.admissionError(request.copy(snapshotId = id), null, true))
        }
        for (id in listOf(0L, -1L)) {
            assertEquals(Error.INVALID_REQUEST, SnapshotRescanContract.admissionError(request.copy(nodeId = id), target, false))
        }
        assertEquals(Error.INVALID_REQUEST, SnapshotRescanContract.admissionError(request.copy(expectedRevision = -1), target, false))
        assertEquals(Error.INVALID_REQUEST, SnapshotRescanContract.admissionError(request.copy(expectedRevision = Long.MAX_VALUE), target, false))
    }

    @Test fun admissionAndPrecommitChecksRejectEveryProtectedBoundary() {
        val cases = listOf(
            null to Error.SNAPSHOT_NOT_FOUND,
            target.copy(available = false) to Error.SNAPSHOT_UNAVAILABLE,
            target.copy(retained = true) to Error.RETAINED,
            target.copy(revision = 8) to Error.STALE_REVISION,
            target.copy(nodeExists = false) to Error.NODE_NOT_FOUND,
            target.copy(directory = false) to Error.NOT_DIRECTORY,
            target.copy(compatible = false) to Error.INCOMPATIBLE_IDENTITY,
        )
        for ((current, error) in cases) {
            assertEquals(error, SnapshotRescanContract.admissionError(request, current, false))
        }
        assertEquals(Error.BUSY, SnapshotRescanContract.admissionError(request, target, true))
        assertNull(SnapshotRescanContract.admissionError(request, target, false))
        assertEquals(Error.STALE_REVISION, SnapshotRescanContract.admissionError(request, target.copy(revision = 9), false))
    }

    @Test fun refusesMissingOrReplacedTargetWithoutTreatingItAsAnEmptyDirectory() {
        assertEquals(Error.TARGET_MISSING, SnapshotRescanContract.resultError(LiveTarget.MISSING, "failed", false, 1))
        assertEquals(Error.TARGET_TYPE_CHANGED, SnapshotRescanContract.resultError(LiveTarget.OTHER, "completed", true, 0))
    }

    @Test fun requiresCompleteIssueFreeSubtreeAndRecognizesProducerFailure() {
        assertNull(SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "completed", true, 0))
        assertEquals(Error.INCOMPLETE_SCAN, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "partial", true, 0))
        assertEquals(Error.INCOMPLETE_SCAN, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "completed", false, 0))
        assertEquals(Error.INCOMPLETE_SCAN, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "completed", true, 1))
        assertEquals(Error.SCAN_FAILED, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "failed", false, 1))
        assertEquals(Error.CANCELLED, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "cancelled", false, 0))
        assertEquals(Error.PROCESS_INTERRUPTED, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "interrupted", false, 0))
        assertEquals(Error.PROTOCOL_ERROR, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "unknown", true, 0))
        assertEquals(Error.PROTOCOL_ERROR, SnapshotRescanContract.resultError(LiveTarget.DIRECTORY, "completed", true, -1))
    }

    @Test fun transitionMatrixForbidsSkippedPhasesAndReopeningTerminalTasks() {
        val success = listOf(State.PREPARING, State.SCANNING, State.VALIDATING, State.APPLYING, State.COMPLETED)
        val failures = setOf(State.FAILED, State.CANCELLED, State.INTERRUPTED, State.STALE)
        for (from in State.entries) for (to in State.entries) {
            val next = success.indexOf(from).let { if (it in 0..3) success[it + 1] else null }
            assertEquals("$from -> $to", !from.terminal && (to == next || to in failures), SnapshotRescanContract.canTransition(from, to))
        }
    }

    @Test fun cancellationBeforeCommitIsOnlyARequestAndAfterCommitCannotUndoSuccess() {
        for (state in State.entries) {
            assertEquals(Cancellation.ALREADY_COMPLETED, SnapshotRescanContract.cancellation(state, 8))
            if (state != State.COMPLETED) {
                assertEquals(if (state.terminal) Cancellation.ALREADY_TERMINAL else Cancellation.REQUESTED,
                    SnapshotRescanContract.cancellation(state, null))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotRescanContract.cancellation(State.COMPLETED, null) }
    }

    @Test fun recoveryUsesDurableCommitMarkerInsteadOfLastPublishedStatus() {
        for (state in State.entries) {
            assertEquals(State.COMPLETED, SnapshotRescanContract.recoveredState(state, 8))
            if (state != State.COMPLETED) {
                assertEquals(if (state.terminal) state else State.INTERRUPTED, SnapshotRescanContract.recoveredState(state, null))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotRescanContract.recoveredState(State.COMPLETED, null) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotRescanContract.recoveredState(State.APPLYING, 0) }
    }

    @Test fun publicMachineValuesAreUniqueAndStable() {
        assertEquals("preparing,scanning,validating,applying,completed,failed,cancelled,interrupted,stale",
            State.entries.joinToString(",") { it.wireName })
        assertEquals("invalid-request,snapshot-not-found,node-not-found,snapshot-unavailable,retained,busy," +
            "stale-revision,not-directory,incompatible-identity,target-missing,target-type-changed,root-unavailable," +
            "incomplete-scan,scan-failed,protocol-error,storage-error,apply-failed,cancelled,process-interrupted",
            Error.entries.joinToString(",") { it.wireName })
        assertEquals(Error.entries.size, Error.entries.map { it.wireName }.toSet().size)
        assertEquals(Cancellation.entries.size, Cancellation.entries.map { it.wireName }.toSet().size)
        assertEquals("stale-revision", Error.STALE_REVISION.wireName)
        assertEquals("incomplete-scan", Error.INCOMPLETE_SCAN.wireName)
        assertEquals("already-completed", Cancellation.ALREADY_COMPLETED.wireName)
    }
}
