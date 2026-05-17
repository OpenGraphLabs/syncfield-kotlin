package io.opengraph.syncfield.insta360

/**
 * Cached state of the camera's phone-authorization decision.
 *
 * Mirrors `PhoneAuthorizationCacheState.swift`. Stored per-camera in
 * `Insta360IdentityStore` keyed by `serialLast6`.
 */
sealed class PhoneAuthorizationCacheState {
    /** No record on disk; the camera has never been authorized this session. */
    object Unknown : PhoneAuthorizationCacheState()

    /** Authorized at the given epoch milliseconds. */
    data class Authorized(val atEpochMs: Long) : PhoneAuthorizationCacheState()

    /** Camera rejected or timed-out the most recent prompt — must retry. */
    object Failed : PhoneAuthorizationCacheState()
}

/**
 * Probe result returned by the camera when we issue `requestCameraPermission`.
 *
 * Mirrors `Insta360PhoneAuthorizationProbeResult` (internal in Swift).
 */
internal enum class Insta360PhoneAuthorizationProbeResult {
    AUTHORIZED,
    UNAUTHORIZED,
    SYSTEM_BUSY,
    CONNECTED_BY_OTHER_PHONE,
    CONNECTED_BY_OTHER_WATCH,
    CONNECTED_BY_OTHER_CYCLOCOMPUTER,
}

/** Initial action the controller takes based on the camera's initial probe state. */
internal sealed class Insta360PhoneAuthorizationInitialAction {
    object Authorized : Insta360PhoneAuthorizationInitialAction()
    object WaitForUserDecision : Insta360PhoneAuthorizationInitialAction()
    data class Fail(val reason: Insta360PhoneAuthorizationProbeResult) :
        Insta360PhoneAuthorizationInitialAction()
}

/** Outcome of the user's tap on the camera screen. */
internal enum class Insta360PhoneAuthorizationUserResult {
    UNKNOWN,
    SUCCESS,
    REJECT,
    TIMEOUT,
    SYSTEM_BUSY,
}

/** Map OneSDK `state` int → initial action. Mirrors Swift `insta360PhoneAuthorizationInitialAction`. */
internal fun insta360PhoneAuthorizationInitialAction(rawState: Int): Insta360PhoneAuthorizationInitialAction =
    when (rawState) {
        0 -> Insta360PhoneAuthorizationInitialAction.Authorized
        1 -> Insta360PhoneAuthorizationInitialAction.WaitForUserDecision
        2 -> Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.SYSTEM_BUSY)
        3 -> Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.CONNECTED_BY_OTHER_PHONE)
        4 -> Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.CONNECTED_BY_OTHER_WATCH)
        5 -> Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.CONNECTED_BY_OTHER_CYCLOCOMPUTER)
        else -> Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.UNAUTHORIZED)
    }

/** Map OneSDK callback `result` int → user result. Mirrors Swift `insta360PhoneAuthorizationUserResult`. */
internal fun insta360PhoneAuthorizationUserResult(rawResult: Int): Insta360PhoneAuthorizationUserResult =
    when (rawResult) {
        1 -> Insta360PhoneAuthorizationUserResult.SUCCESS
        2 -> Insta360PhoneAuthorizationUserResult.REJECT
        3 -> Insta360PhoneAuthorizationUserResult.TIMEOUT
        4 -> Insta360PhoneAuthorizationUserResult.SYSTEM_BUSY
        else -> Insta360PhoneAuthorizationUserResult.UNKNOWN
    }
