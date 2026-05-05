package tech.lenooby09.offlineAuth.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the boot-time decision logic for selecting `authMode`:
 *
 * `authMode == BLUESKY` iff `config.blueskyEnabled && config.validateBlueskyConfig()`.
 *
 * Misconfiguration must fall back to password mode so the server stays usable
 * (validation logs a warning and returns false).
 */
class OfflineAuthConfigBlueskyTest {

	@Test
	fun testValidateReturnsFalseWhenBlueskyDisabled() {
		val cfg = OfflineAuthConfig(blueskyEnabled = false)
		assertFalse(cfg.validateBlueskyConfig())
	}

	@Test
	fun testValidateReturnsFalseWhenPublicUrlIsBlank() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "",
		)
		assertFalse(cfg.validateBlueskyConfig())
	}

	@Test
	fun testValidateReturnsFalseWhenPublicUrlIsHttpAndNotLoopback() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "http://example.com",
		)
		assertFalse(cfg.validateBlueskyConfig())
	}

	@Test
	fun testValidateReturnsFalseWhenWhitelistIsBlank() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "",
			blueskyPublicUrl = "https://auth.example.com",
		)
		assertFalse(cfg.validateBlueskyConfig())
	}

	@Test
	fun testValidateAcceptsHttpsPublicUrlAndNonEmptyList() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
		)
		assertTrue(cfg.validateBlueskyConfig())
	}

	@Test
	fun testValidateAcceptsLoopbackHttpForLocalDev() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "http://127.0.0.1:8080",
		)
		assertTrue(cfg.validateBlueskyConfig())
	}

	@Test
	fun testValidateAcceptsLocalhostLoopback() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "https://bsky.app/profile/alice.bsky.social/lists/3kxx",
			blueskyPublicUrl = "http://localhost:8080",
		)
		assertTrue(cfg.validateBlueskyConfig())
	}

	@Test
	fun testDerivedClientIdAndRedirectUri() {
		val cfg = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com",
		)
		// `client_id` must be the metadata URL with a content-derived `?v=`
		// query param (cache-bust against `@atproto/oauth-provider`'s 10-min
		// `clientMetadataCache`).
		val clientId = cfg.blueskyClientId
		assertTrue(
			clientId.startsWith("https://auth.example.com/oauth-client-metadata.json?v="),
			"client_id should be metadata URL with `?v=<hash>` cache-bust suffix, was '$clientId'",
		)
		assertEquals("https://auth.example.com/bluesky/callback", cfg.blueskyRedirectUri)
	}

	@Test
	fun testDerivedUrlsTrimTrailingSlash() {
		val cfg = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com/",
		)
		val clientId = cfg.blueskyClientId
		assertTrue(
			clientId.startsWith("https://auth.example.com/oauth-client-metadata.json?v="),
			"client_id should trim trailing slash and add cache-bust suffix, was '$clientId'",
		)
		assertEquals("https://auth.example.com/bluesky/callback", cfg.blueskyRedirectUri)
	}

	/**
	 * The cache-bust version tag must be deterministic and content-derived:
	 * configs with identical metadata-relevant fields must produce the same
	 * version, so PDSes don't get a brand-new `client_id` URL on every
	 * server restart (which would pollute the auth server's metadata cache).
	 */
	@Test
	fun testClientMetadataVersionIsStableForSameContent() {
		val cfg1 = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
			blueskyClientName = "OfflineAuth Minecraft Server",
		)
		val cfg2 = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
			blueskyClientName = "OfflineAuth Minecraft Server",
		)
		assertEquals(cfg1.blueskyClientMetadataVersion, cfg2.blueskyClientMetadataVersion)
		assertEquals(cfg1.blueskyClientId, cfg2.blueskyClientId)
	}

	/**
	 * Changing `bluesky-scope` must change the cache-bust version. After the
	 * deprecation of `transition:generic` the realistic motion is the inverse
	 * of the original bug: an operator drops the deprecated grant in favor of
	 * the modern `rpc:...` lexicon-permission scope (the new default), and
	 * the PDS would otherwise keep its 10-minute-cached old metadata, blowing
	 * up authorize with `invalid_scope: Scope "transition:generic" is not
	 * declared in the client metadata`. The version-tagged URL forces a
	 * fresh fetch. The deprecated token is still used here as a fixture
	 * because the test exercises the cache-bust contract, not validation.
	 */
	@Test
	fun testClientMetadataVersionChangesWhenScopeChanges() {
		val cfgOld = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto transition:generic",
		)
		val cfgNew = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
		)
		assertFalse(
			cfgOld.blueskyClientMetadataVersion == cfgNew.blueskyClientMetadataVersion,
			"version must change when scope changes — otherwise PDS keeps stale metadata",
		)
		assertFalse(
			cfgOld.blueskyClientId == cfgNew.blueskyClientId,
			"client_id URL must change when scope changes — otherwise PDS keeps stale metadata",
		)
	}

	@Test
	fun testClientMetadataVersionChangesWhenClientNameChanges() {
		val cfg1 = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com",
			blueskyClientName = "Server A",
		)
		val cfg2 = OfflineAuthConfig(
			blueskyPublicUrl = "https://auth.example.com",
			blueskyClientName = "Server B",
		)
		assertFalse(cfg1.blueskyClientMetadataVersion == cfg2.blueskyClientMetadataVersion)
	}

	@Test
	fun testClientMetadataVersionIsEmptyWhenPublicUrlIsBlank() {
		val cfg = OfflineAuthConfig(blueskyPublicUrl = "")
		assertEquals("", cfg.blueskyClientMetadataVersion)
	}

	/**
	 * The default scope must be the modern `rpc:...` lexicon-permission grant.
	 * The legacy `transition:generic` token is now deprecated and is rejected
	 * by [OfflineAuthConfig.validateBlueskyConfig]; operators stuck on an
	 * older PDS that only understands `transition:generic` should pin a
	 * previous OfflineAuth release rather than re-enable the deprecated grant.
	 */
	@Test
	fun testDefaultBlueskyScopeIsRpcLexiconPermission() {
		assertEquals(
			"atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
			OfflineAuthConfig().blueskyScope,
		)
	}

	/**
	 * `transition:generic` is the deprecated legacy ATProto OAuth grant. When
	 * present as a whole token in `bluesky-scope` the validator must refuse
	 * Bluesky mode so misconfigured upgrades fall back to password mode
	 * rather than silently pinning the deployment to the deprecated grant.
	 */
	@Test
	fun validateRejectsTransitionGenericExactToken() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto transition:generic",
		)
		assertFalse(cfg.validateBlueskyConfig())
	}

	/**
	 * Detection is case-insensitive so an operator who copy-pasted a mangled
	 * scope (e.g. `Transition:Generic`) is still caught and migrated.
	 */
	@Test
	fun validateRejectsTransitionGenericCaseInsensitive() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto Transition:Generic",
		)
		assertFalse(cfg.validateBlueskyConfig())
	}

	/**
	 * A scope string that mixes the deprecated `transition:generic` token
	 * with otherwise-valid grants must still be rejected — a partial
	 * migration that leaves the deprecated token in place is exactly the
	 * misconfiguration this validator exists to catch.
	 */
	@Test
	fun validateRejectsTransitionGenericMixedWithOtherTokens() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto transition:generic rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
		)
		assertFalse(cfg.validateBlueskyConfig())
	}

	@Test
	fun validateAcceptsRpcLexiconPermissionScope() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
		)
		assertTrue(cfg.validateBlueskyConfig())
	}

	/**
	 * Detection is whole-token: a scope token that merely contains the
	 * substring `transition:generic` inside a longer literal (hypothetical,
	 * e.g. a future grant grammar) must not falsely trip the deprecation
	 * check.
	 */
	@Test
	fun validateDoesNotFalsePositiveOnSubstringMatch() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = "atproto rpc:foo.transition:generic",
		)
		assertTrue(cfg.validateBlueskyConfig())
	}
}
