package io.opengraph.syncfield.insta360.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstaLogTest {

    @Test
    fun formatFields_emptyMap_returnsEmptyString() {
        assertThat(InstaLog.formatFields(emptyMap())).isEmpty()
    }

    @Test
    fun formatFields_sortsKeys() {
        val out = InstaLog.formatFields(mapOf("zebra" to 1, "alpha" to "x", "mike" to true))
        assertThat(out).isEqualTo("alpha=x mike=true zebra=1")
    }

    @Test
    fun stringify_unwrapsNullAsNilLiteral() {
        assertThat(InstaLog.stringify(null)).isEqualTo("nil")
    }

    @Test
    fun stringify_quotesStringWithSpace() {
        assertThat(InstaLog.stringify("hello world")).isEqualTo("\"hello world\"")
    }

    @Test
    fun stringify_quotesEmptyString() {
        assertThat(InstaLog.stringify("")).isEqualTo("\"\"")
    }

    @Test
    fun stringify_bareStringWithoutWhitespace() {
        assertThat(InstaLog.stringify("camera1")).isEqualTo("camera1")
    }

    @Test
    fun stringify_boolean() {
        assertThat(InstaLog.stringify(true)).isEqualTo("true")
        assertThat(InstaLog.stringify(false)).isEqualTo("false")
    }

    @Test
    fun stringify_listJoinedByComma() {
        assertThat(InstaLog.stringify(listOf("a", "b", "c"))).isEqualTo("[a,b,c]")
    }

    @Test
    fun stringify_nestedListWithMixed() {
        assertThat(InstaLog.stringify(listOf("hi there", null, 42))).isEqualTo("[\"hi there\",nil,42]")
    }
}
