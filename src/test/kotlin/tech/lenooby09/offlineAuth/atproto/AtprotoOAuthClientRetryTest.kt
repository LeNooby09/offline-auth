package tech.lenooby09.offlineAuth.atproto

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that PAR survives a server that rotates the `DPoP-Nonce` header
 * between successive requests. RFC 9449 explicitly allows this, so the OAuth
 * client must retry up to twice (initial attempt + 2 nonce-driven retries)
 * with a freshly built DPoP proof on each round before giving up.
 *
 * Regression guard for the "PDS unknown error" investigation: a single retry
 * was insufficient when the auth server issues a fresh nonce in *every*
 * response.
 */
class AtprotoOAuthClientRetryTest {

	private val handleResolutionJson = """{"did":"did:plc:abc"}"""

	private val didDocumentJson = """
		{
			"id": "did:plc:abc",
			"alsoKnownAs": ["at://alice.test"],
			"service": [
				{
					"id": "#atproto_pds",
					"type": "AtprotoPersonalDataServer",
					"serviceEndpoint": "https://pds.example.com"
				}
			]
		}
	""".trimIndent()

	private val pdsMetadataJson = """
		{"authorization_servers": ["https://auth.example.com"]}
	""".trimIndent()

	private val authServerMetadataJson = """
		{
			"issuer": "https://auth.example.com",
			"authorization_endpoint": "https://auth.example.com/authorize",
			"token_endpoint": "https://auth.example.com/token",
			"pushed_authorization_request_endpoint": "https://auth.example.com/par"
		}
	""".trimIndent()

	private val parSuccessJson = """
		{"request_uri": "urn:ietf:params:oauth:request_uri:abc", "expires_in": 300}
	""".trimIndent()

	private val parErrorJson = """{"error": "use_dpop_nonce"}"""

	@Test
	fun startAuthorizationSucceedsAfterTwoDpopNonceRetries() = runBlocking {
		val parAttempts = AtomicInteger(0)

		val mockEngine = MockEngine { request ->
			val url = request.url.toString()
			when {
				url.startsWith("https://bsky.social/xrpc/com.atproto.identity.resolveHandle") -> respond(
					content = handleResolutionJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://plc.directory/did:plc:abc" -> respond(
					content = didDocumentJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://pds.example.com/.well-known/oauth-protected-resource" -> respond(
					content = pdsMetadataJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://auth.example.com/.well-known/oauth-authorization-server" -> respond(
					content = authServerMetadataJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://auth.example.com/par" -> {
					val n = parAttempts.getAndIncrement()
					when (n) {
						0 -> respond(
							content = parErrorJson,
							status = HttpStatusCode.BadRequest,
							headers = headersOf(
								HttpHeaders.ContentType to listOf("application/json"),
								"DPoP-Nonce" to listOf("nonce-round-1"),
							),
						)
						1 -> respond(
							content = parErrorJson,
							status = HttpStatusCode.BadRequest,
							headers = headersOf(
								HttpHeaders.ContentType to listOf("application/json"),
								"DPoP-Nonce" to listOf("nonce-round-2"),
							),
						)
						else -> respond(
							content = parSuccessJson,
							status = HttpStatusCode.OK,
							headers = headersOf(HttpHeaders.ContentType, "application/json"),
						)
					}
				}
				else -> error("Unexpected URL hit by MockEngine: $url")
			}
		}

		val httpClient = HttpClient(mockEngine) {
			install(ContentNegotiation) {
				json(Json { ignoreUnknownKeys = true })
			}
		}

		val client = AtprotoOAuthClient(
			httpClient = httpClient,
			clientId = "https://app.example.com/oauth-client-metadata.json",
			redirectUri = "https://app.example.com/bluesky/callback",
			scope = "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
		)

		val result = client.startAuthorization("alice.test")

		assertEquals(
			3,
			parAttempts.get(),
			"PAR endpoint should be hit 3 times: initial attempt + 2 nonce-driven retries",
		)
		assertEquals("did:plc:abc", result.did)
		assertEquals("alice.test", result.handle)
		assertEquals("https://auth.example.com", result.authServerIssuer)
		assertEquals("https://auth.example.com/token", result.tokenEndpoint)
		assertEquals("https://pds.example.com", result.pdsUrl)
		assertTrue(
			result.authorizeUrl.startsWith("https://auth.example.com/authorize?"),
			"authorize URL must point at the auth server's authorize endpoint",
		)
		assertTrue(
			result.authorizeUrl.contains("request_uri="),
			"authorize URL must carry the PAR request_uri",
		)

		httpClient.close()
	}

	@Test
	fun startAuthorizationGivesUpAfterTwoRetriesIfNonceKeepsRotating() = runBlocking {
		val parAttempts = AtomicInteger(0)

		val mockEngine = MockEngine { request ->
			val url = request.url.toString()
			when {
				url.startsWith("https://bsky.social/xrpc/com.atproto.identity.resolveHandle") -> respond(
					content = handleResolutionJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://plc.directory/did:plc:abc" -> respond(
					content = didDocumentJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://pds.example.com/.well-known/oauth-protected-resource" -> respond(
					content = pdsMetadataJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://auth.example.com/.well-known/oauth-authorization-server" -> respond(
					content = authServerMetadataJson,
					status = HttpStatusCode.OK,
					headers = headersOf(HttpHeaders.ContentType, "application/json"),
				)
				url == "https://auth.example.com/par" -> {
					val n = parAttempts.getAndIncrement()
					respond(
						content = parErrorJson,
						status = HttpStatusCode.BadRequest,
						headers = headersOf(
							HttpHeaders.ContentType to listOf("application/json"),
							"DPoP-Nonce" to listOf("nonce-round-${n + 1}"),
						),
					)
				}
				else -> error("Unexpected URL hit by MockEngine: $url")
			}
		}

		val httpClient = HttpClient(mockEngine) {
			install(ContentNegotiation) {
				json(Json { ignoreUnknownKeys = true })
			}
		}

		val client = AtprotoOAuthClient(
			httpClient = httpClient,
			clientId = "https://app.example.com/oauth-client-metadata.json",
			redirectUri = "https://app.example.com/bluesky/callback",
			scope = "atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview",
		)

		try {
			client.startAuthorization("alice.test")
			error("expected IllegalStateException to be thrown after retries are exhausted")
		} catch (e: IllegalStateException) {
			assertTrue(
				e.message?.startsWith("PAR request failed") == true,
				"unexpected error message: ${e.message}",
			)
		}

		assertEquals(
			3,
			parAttempts.get(),
			"PAR should be tried initial + 2 retries = 3 times before giving up",
		)

		httpClient.close()
	}
}
