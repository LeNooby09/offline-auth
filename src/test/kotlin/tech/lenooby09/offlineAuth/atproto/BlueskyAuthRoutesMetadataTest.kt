package tech.lenooby09.offlineAuth.atproto

import tech.lenooby09.offlineAuth.config.OfflineAuthConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the shape of the OAuth client metadata document advertised at
 * `/oauth-client-metadata.json` against the failure modes that triggered the
 * "unknown error on PDS authorize page" bug:
 *
 * - `application_type` must be derived from the redirect URI (HTTPS → `web`,
 *   loopback → `native`); the previous unconditional `"native"` is rejected by
 *   `@atproto/oauth-provider`-based PDSes for non-loopback HTTPS clients.
 * - `grant_types` must include both `authorization_code` and `refresh_token` —
 *   the official Bluesky reference client publishes both, and some validators
 *   treat the missing `refresh_token` as a hard error.
 * - The default `scope` is the modern `rpc:...` lexicon-permission grant
 *   (`atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview`).
 *   The legacy `transition:generic` grant is rejected at startup by
 *   `OfflineAuthConfig.validateBlueskyConfig` (not by the metadata builder)
 *   so misconfigured deployments fall back to password mode; this test only
 *   asserts the published shape, not the validator.
 */
class BlueskyAuthRoutesMetadataTest {

	@Test
	fun deriveApplicationTypeHttpsRedirectIsWeb() {
		assertEquals("web", deriveApplicationType("https://auth.example.com/bluesky/callback"))
	}

	@Test
	fun deriveApplicationTypeLoopbackIp4IsNative() {
		assertEquals("native", deriveApplicationType("http://127.0.0.1:8080/bluesky/callback"))
	}

	@Test
	fun deriveApplicationTypeLocalhostIsNative() {
		assertEquals("native", deriveApplicationType("http://localhost:8080/bluesky/callback"))
	}

	@Test
	fun deriveApplicationTypeLoopbackIp6IsNative() {
		assertEquals("native", deriveApplicationType("http://[::1]:8080/bluesky/callback"))
	}

	@Test
	fun deriveApplicationTypeIsCaseInsensitive() {
		// Operators may write `HTTP://127.0.0.1` or `HTTPS://...` — the helper
		// normalizes the scheme/host before deciding.
		assertEquals("native", deriveApplicationType("HTTP://127.0.0.1:8080/bluesky/callback"))
		assertEquals("web", deriveApplicationType("HTTPS://AUTH.EXAMPLE.COM/bluesky/callback"))
	}

	@Test
	fun buildClientMetadataForHttpsPublicUrl() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
		)
		val metadata = buildClientMetadata(cfg)

		// `client_id` carries a `?v=<hash>` cache-bust query so PDSes which
		// cache client metadata by `client_id` re-fetch on any config change
		// (see OfflineAuthConfig.blueskyClientMetadataVersion).
		assertTrue(
			metadata.clientId.startsWith("https://auth.example.com/oauth-client-metadata.json?v="),
			"client_id should be the metadata URL with `?v=<hash>` cache-bust suffix, was '${metadata.clientId}'",
		)
		assertEquals("https://auth.example.com", metadata.clientUri)
		assertEquals(listOf("https://auth.example.com/bluesky/callback"), metadata.redirectUris)
		assertEquals(listOf("authorization_code", "refresh_token"), metadata.grantTypes)
		assertEquals(listOf("code"), metadata.responseTypes)
		assertEquals("atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview", metadata.scope)
		assertEquals("none", metadata.tokenEndpointAuthMethod)
		assertEquals("web", metadata.applicationType)
		assertEquals(true, metadata.dpopBoundAccessTokens)
	}

	/**
	 * Locks in the cache-bust contract: the `client_id` in the published
	 * metadata document must match `config.blueskyClientId` exactly, so PDSes
	 * which validate the body's `client_id` against the URL they fetched from
	 * don't see a mismatch.
	 */
	@Test
	fun buildClientMetadataClientIdMatchesConfigClientId() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
		)
		val metadata = buildClientMetadata(cfg)
		assertEquals(cfg.blueskyClientId, metadata.clientId)
	}

	@Test
	fun buildClientMetadataForLoopbackPublicUrl() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "http://127.0.0.1:8080",
		)
		val metadata = buildClientMetadata(cfg)

		assertEquals("native", metadata.applicationType)
		assertEquals(listOf("authorization_code", "refresh_token"), metadata.grantTypes)
		assertEquals("atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview", metadata.scope)
	}

	@Test
	fun buildClientMetadataPropagatesCustomScope() {
		// A custom (non-default) scope must still flow through to the published
		// metadata document unchanged. Use a hypothetical lexicon-permission
		// scope distinct from the new default to prove propagation.
		val customScope = "atproto rpc:app.bsky.feed.getTimeline?aud=did:web:api.bsky.app#bsky_appview"
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
			blueskyScope = customScope,
		)
		val metadata = buildClientMetadata(cfg)

		assertEquals(customScope, metadata.scope)
	}

	@Test
	fun buildClientMetadataPropagatesCustomClientName() {
		val cfg = OfflineAuthConfig(
			blueskyEnabled = true,
			blueskyWhitelistList = "at://did:plc:abc/app.bsky.graph.list/3kxx",
			blueskyPublicUrl = "https://auth.example.com",
			blueskyClientName = "Custom Server Name",
		)
		val metadata = buildClientMetadata(cfg)

		assertEquals("Custom Server Name", metadata.clientName)
	}
}
