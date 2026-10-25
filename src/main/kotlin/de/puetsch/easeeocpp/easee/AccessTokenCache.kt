package de.puetsch.easeeocpp.easee

import java.time.Clock
import java.time.Instant

private const val TOKEN_REFRESH_MARGIN_SECONDS = 60L

internal class AccessTokenCache(
    private val clock: Clock,
    private val login: () -> LoginToken,
) {
    private var cached: CachedAccessToken? = null

    @Synchronized
    fun currentBearerToken(): String {
        val reusable = cached?.takeIf { isStillReusable(it) }
        return (reusable ?: renewToken()).value
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    private fun isStillReusable(token: CachedAccessToken): Boolean =
        clock.instant().isBefore(token.reusableUntil)

    private fun renewToken(): CachedAccessToken {
        val loginToken = login()
        val renewed = CachedAccessToken(
            value = loginToken.value,
            reusableUntil = clock.instant().plusSeconds(loginToken.expiresInSeconds - TOKEN_REFRESH_MARGIN_SECONDS),
        )
        cached = renewed
        return renewed
    }
}

private data class CachedAccessToken(val value: String, val reusableUntil: Instant)
