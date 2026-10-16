// SPDX-License-Identifier: Apache-2.0
package local.cardmin

/** Backend constants, taken from the analysed spendit app (client-spec/api.md §1). */
object Config {
    const val BASE_URL = "https://app.api.spendit.de"

    // The official app also sends "Platform: android" and "Build-Number: 220" on every request.
    // They are left out on purpose; re-add them in Api.defaultHeaders if the server insists.
    const val LANGUAGE = "de"
    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:152.0) Gecko/20100101 Firefox/152.0"

    /** Paths that are sent without an Authorization header (exact match, as in the original). */
    val UNAUTHENTICATED_PATHS = setOf(
        "/registration", "/activation", "/registration/code",
        "/email/change/confirmation", "/login", "/password", "/refreshToken",
    )

    const val TAN_RESEND_SECONDS = 30
    const val PENDING_POLL_MS = 15_000L
    const val MAX_BOUND_DEVICES = 5
}
