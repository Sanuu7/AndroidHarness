package com.androidharness.app.data.github

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Pure protocol helpers, shared by sign-in and callback validation. */
object GitHubOAuthProtocol {
    fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun validState(expected: String, actual: String?, createdAt: Long, now: Long): Boolean =
        actual != null && now >= createdAt && now - createdAt < 600_000 &&
            MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())

    fun authorizeUrl(clientId: String, redirect: String, state: String, verifier: String, scopes: Set<String>): String =
        "https://github.com/login/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("scope", (setOf("repo", "offline_access") + scopes.intersect(
                setOf("workflow", "gist", "read:org", "delete_repo"))).sorted().joinToString(" "))
            .addQueryParameter("prompt", "select_account")
            .build().toString()
}
