package com.anthroid.remote

/**
 * Represents a discoverable remote agent session.
 */
data class RemoteSessionInfo(
    val sessionKey: String,
    val displayName: String?,
    val lastActivity: Long,
    val status: String,
    val source: Source,
    val hostname: String? = null,
) {
    enum class Source { OPENCLAW, SSH_TMUX }

    val label: String
        get() = displayName ?: sessionKey

    val sourceTag: String
        get() = when (source) {
            Source.OPENCLAW -> "OC"
            Source.SSH_TMUX -> "SSH"
        }
}
