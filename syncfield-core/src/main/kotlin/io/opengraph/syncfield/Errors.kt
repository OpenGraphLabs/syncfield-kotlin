package io.opengraph.syncfield

/**
 * Top-level errors raised by [SessionOrchestrator]. Mirrors
 * `Sources/SyncField/Errors.swift` in syncfield-swift.
 */
sealed class SessionError(message: String) : Exception(message) {

    class InvalidTransition(val from: SessionState, val to: SessionState) :
        SessionError("SessionError: cannot transition from $from to $to")

    class DuplicateStreamId(val streamId: String) :
        SessionError("SessionError: duplicate streamId '$streamId'")

    object NoStreamsRegistered :
        SessionError("SessionError: no streams registered")

    class StartFailed(cause: Throwable, val rolledBack: List<String>) :
        SessionError(
            "SessionError: startRecording failed (${cause.message}); rolled back $rolledBack"
        ) {
        init { initCause(cause) }
    }

    object NotRunning :
        SessionError("SessionError: operation requires the session to be running")
}

/**
 * Wraps a stream-level failure with the offending stream's id so the
 * orchestrator can surface which adapter went wrong.
 */
class StreamError(
    val streamId: String,
    cause: Throwable,
) : Exception("StreamError[$streamId]: ${cause.message}", cause)
