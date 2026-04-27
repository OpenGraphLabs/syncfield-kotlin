package io.opengraph.syncfield

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionState {
    @SerialName("idle")       Idle,
    @SerialName("connected")  Connected,
    @SerialName("recording")  Recording,
    @SerialName("stopping")   Stopping,
    @SerialName("ingesting")  Ingesting,
}
