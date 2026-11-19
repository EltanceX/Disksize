package com.example.disksize.scan

/** Shared task contract for ordinary-UID persistence, coordinator admission, and status responses. */
internal object SnapshotRescanContract {
    data class Request(val snapshotId: String, val nodeId: Long, val expectedRevision: Long)

    data class Target(
        val revision: Long,
        val available: Boolean = true,
        val retained: Boolean = false,
        val directory: Boolean = true,
        val compatible: Boolean = true,
        val nodeExists: Boolean = true,
    )

    enum class State(val wireName: String, val terminal: Boolean = false) {
        PREPARING("preparing"), SCANNING("scanning"), VALIDATING("validating"), APPLYING("applying"),
        COMPLETED("completed", true), FAILED("failed", true), CANCELLED("cancelled", true),
        INTERRUPTED("interrupted", true), STALE("stale", true),
    }

    enum class Error(val wireName: String) {
        INVALID_REQUEST("invalid-request"), SNAPSHOT_NOT_FOUND("snapshot-not-found"),
        NODE_NOT_FOUND("node-not-found"),
        SNAPSHOT_UNAVAILABLE("snapshot-unavailable"), RETAINED("retained"), BUSY("busy"),
        STALE_REVISION("stale-revision"), NOT_DIRECTORY("not-directory"),
        INCOMPATIBLE_IDENTITY("incompatible-identity"), TARGET_MISSING("target-missing"),
        TARGET_TYPE_CHANGED("target-type-changed"), ROOT_UNAVAILABLE("root-unavailable"),
        INCOMPLETE_SCAN("incomplete-scan"), SCAN_FAILED("scan-failed"),
        PROTOCOL_ERROR("protocol-error"), STORAGE_ERROR("storage-error"), APPLY_FAILED("apply-failed"),
        CANCELLED("cancelled"), PROCESS_INTERRUPTED("process-interrupted"),
    }

    enum class LiveTarget { DIRECTORY, MISSING, OTHER }
    enum class Cancellation(val wireName: String) {
        REQUESTED("cancellation-requested"), ALREADY_COMPLETED("already-completed"),
        ALREADY_TERMINAL("already-terminal"),
    }

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    // Call again immediately before applying, with the current task owning the write slot.
    fun admissionError(request: Request, target: Target?, conflictingTask: Boolean): Error? = when {
        !uuid.matches(request.snapshotId) || request.nodeId <= 0 || request.expectedRevision !in 0 until Long.MAX_VALUE -> Error.INVALID_REQUEST
        target == null -> Error.SNAPSHOT_NOT_FOUND
        conflictingTask -> Error.BUSY
        !target.available -> Error.SNAPSHOT_UNAVAILABLE
        target.retained -> Error.RETAINED
        target.revision != request.expectedRevision -> Error.STALE_REVISION
        !target.nodeExists -> Error.NODE_NOT_FOUND
        !target.directory -> Error.NOT_DIRECTORY
        !target.compatible -> Error.INCOMPATIBLE_IDENTITY
        else -> null
    }

    fun resultError(target: LiveTarget, scanState: String, complete: Boolean, issues: Long): Error? = when {
        issues < 0 -> Error.PROTOCOL_ERROR
        scanState == "cancelled" -> Error.CANCELLED
        scanState == "interrupted" -> Error.PROCESS_INTERRUPTED
        target == LiveTarget.MISSING -> Error.TARGET_MISSING
        target == LiveTarget.OTHER -> Error.TARGET_TYPE_CHANGED
        scanState == "failed" -> Error.SCAN_FAILED
        scanState !in setOf("completed", "partial") -> Error.PROTOCOL_ERROR
        scanState != "completed" || !complete || issues != 0L -> Error.INCOMPLETE_SCAN
        else -> null
    }

    fun canTransition(from: State, to: State): Boolean {
        if (from.terminal || from == to) return false
        if (to in setOf(State.FAILED, State.CANCELLED, State.INTERRUPTED, State.STALE)) return true
        return when (from) {
            State.PREPARING -> to == State.SCANNING
            State.SCANNING -> to == State.VALIDATING
            State.VALIDATING -> to == State.APPLYING
            State.APPLYING -> to == State.COMPLETED
            else -> false
        }
    }

    fun recoveredState(state: State, committedRevision: Long?): State {
        if (committedRevision != null) {
            require(committedRevision > 0)
            return State.COMPLETED
        }
        require(state != State.COMPLETED) { "Completed tasks require a durable committed revision" }
        return if (state.terminal) state else State.INTERRUPTED
    }

    fun cancellation(state: State, committedRevision: Long?): Cancellation {
        if (committedRevision != null) {
            require(committedRevision > 0)
            return Cancellation.ALREADY_COMPLETED
        }
        require(state != State.COMPLETED) { "Completed tasks require a durable committed revision" }
        return if (state.terminal) Cancellation.ALREADY_TERMINAL else Cancellation.REQUESTED
    }
}
