package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneAuthorizationCacheStateTest {

    @Test
    fun unknownIsSingleton() {
        assertThat(PhoneAuthorizationCacheState.Unknown).isSameInstanceAs(PhoneAuthorizationCacheState.Unknown)
    }

    @Test
    fun failedIsSingleton() {
        assertThat(PhoneAuthorizationCacheState.Failed).isSameInstanceAs(PhoneAuthorizationCacheState.Failed)
    }

    @Test
    fun authorizedCarriesTimestamp() {
        val state = PhoneAuthorizationCacheState.Authorized(atEpochMs = 1_700_000_000_000L)
        assertThat(state.atEpochMs).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun authorizedEqualityByTimestamp() {
        val a = PhoneAuthorizationCacheState.Authorized(1L)
        val b = PhoneAuthorizationCacheState.Authorized(1L)
        val c = PhoneAuthorizationCacheState.Authorized(2L)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun initialAction_authorizedForState0() {
        assertThat(insta360PhoneAuthorizationInitialAction(0))
            .isEqualTo(Insta360PhoneAuthorizationInitialAction.Authorized)
    }

    @Test
    fun initialAction_waitForState1() {
        assertThat(insta360PhoneAuthorizationInitialAction(1))
            .isEqualTo(Insta360PhoneAuthorizationInitialAction.WaitForUserDecision)
    }

    @Test
    fun initialAction_failedReasons() {
        assertThat(insta360PhoneAuthorizationInitialAction(2))
            .isEqualTo(Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.SYSTEM_BUSY))
        assertThat(insta360PhoneAuthorizationInitialAction(3))
            .isEqualTo(Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.CONNECTED_BY_OTHER_PHONE))
        assertThat(insta360PhoneAuthorizationInitialAction(4))
            .isEqualTo(Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.CONNECTED_BY_OTHER_WATCH))
        assertThat(insta360PhoneAuthorizationInitialAction(5))
            .isEqualTo(Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.CONNECTED_BY_OTHER_CYCLOCOMPUTER))
    }

    @Test
    fun initialAction_unknownStateFallsBackToUnauthorized() {
        assertThat(insta360PhoneAuthorizationInitialAction(99))
            .isEqualTo(Insta360PhoneAuthorizationInitialAction.Fail(Insta360PhoneAuthorizationProbeResult.UNAUTHORIZED))
    }

    @Test
    fun userResult_mappings() {
        assertThat(insta360PhoneAuthorizationUserResult(1)).isEqualTo(Insta360PhoneAuthorizationUserResult.SUCCESS)
        assertThat(insta360PhoneAuthorizationUserResult(2)).isEqualTo(Insta360PhoneAuthorizationUserResult.REJECT)
        assertThat(insta360PhoneAuthorizationUserResult(3)).isEqualTo(Insta360PhoneAuthorizationUserResult.TIMEOUT)
        assertThat(insta360PhoneAuthorizationUserResult(4)).isEqualTo(Insta360PhoneAuthorizationUserResult.SYSTEM_BUSY)
        assertThat(insta360PhoneAuthorizationUserResult(0)).isEqualTo(Insta360PhoneAuthorizationUserResult.UNKNOWN)
        assertThat(insta360PhoneAuthorizationUserResult(99)).isEqualTo(Insta360PhoneAuthorizationUserResult.UNKNOWN)
    }
}
