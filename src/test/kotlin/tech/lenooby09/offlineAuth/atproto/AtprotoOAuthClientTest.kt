package tech.lenooby09.offlineAuth.atproto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Static-helper tests ported from the upstream `intermediate-oauth` test
 * suite. These cover the pure functions: [generateCodeVerifier],
 * [generateCodeChallenge], [computeAccessTokenHash], and [isScopeGranted].
 */
class AtprotoOAuthClientTest {

	@Test
	fun testGenerateCodeVerifierIsUrlSafeAndUnpadded() {
		val v = AtprotoOAuthClient.generateCodeVerifier()
		assertFalse(v.contains('='), "code verifier must be unpadded")
		assertFalse(v.contains('+'), "code verifier must be URL-safe (no +)")
		assertFalse(v.contains('/'), "code verifier must be URL-safe (no /)")
		// 32 bytes -> 43 base64url chars, but Java's Base64 encoder may emit a
		// 44-char string that is later trimmed; allow either length.
		assertTrue(v.length in 42..44, "expected 32-byte code verifier; got length=${v.length}")
	}

	@Test
	fun testGenerateCodeChallengeIsDeterministic() {
		val verifier = "fixed-verifier-test-vector"
		val a = AtprotoOAuthClient.generateCodeChallenge(verifier)
		val b = AtprotoOAuthClient.generateCodeChallenge(verifier)
		assertEquals(a, b, "S256 code challenge must be deterministic")
		assertEquals(43, a.length)
	}

	// RFC 9449 §6.1: ath = base64url(no-padding) of SHA-256(ASCII(access_token))
	// Independently computed: openssl dgst -sha256 -binary then base64 URL-safe no-padding.
	@Test
	fun testComputeAccessTokenHashKnownVector() {
		val token = "test"
		val expected = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg"
		assertEquals(expected, AtprotoOAuthClient.computeAccessTokenHash(token))
	}

	@Test
	fun testComputeAccessTokenHashLength() {
		// SHA-256 produces 32 bytes -> base64url no-padding has 43 characters.
		val hash = AtprotoOAuthClient.computeAccessTokenHash("any-realistic-access-token-value")
		assertEquals(43, hash.length)
		assertFalse(hash.contains('='), "ath must be unpadded")
		assertFalse(hash.contains('+'), "ath must be URL-safe (no +)")
		assertFalse(hash.contains('/'), "ath must be URL-safe (no /)")
	}

	@Test
	fun testIsScopeGrantedAcceptsExactMatch() {
		assertTrue(
			AtprotoOAuthClient.isScopeGranted(
				"atproto account:email",
				"account:email",
			),
		)
	}

	@Test
	fun testIsScopeGrantedRejectsMissingScope() {
		assertFalse(
			AtprotoOAuthClient.isScopeGranted("atproto", "account:email"),
		)
	}

	@Test
	fun testIsScopeGrantedRejectsNullScope() {
		assertFalse(AtprotoOAuthClient.isScopeGranted(null, "account:email"))
	}

	@Test
	fun testIsScopeGrantedRejectsBlankScope() {
		assertFalse(AtprotoOAuthClient.isScopeGranted("   ", "account:email"))
	}

	@Test
	fun testIsScopeGrantedRequiresExactToken() {
		// "account:emailfoo" must not satisfy "account:email"; a substring match is not enough.
		assertFalse(
			AtprotoOAuthClient.isScopeGranted("atproto account:emailfoo", "account:email"),
		)
	}

	@Test
	fun testIsScopeGrantedHandlesExtraWhitespace() {
		assertTrue(
			AtprotoOAuthClient.isScopeGranted("  atproto   account:email   ", "account:email"),
		)
	}
}
