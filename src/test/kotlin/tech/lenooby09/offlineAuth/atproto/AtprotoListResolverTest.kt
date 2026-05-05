package tech.lenooby09.offlineAuth.atproto

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtprotoListResolverTest {

	private val sampleAtUri = "at://did:plc:abc123/app.bsky.graph.list/3kxx"

	@Test
	fun testParseListReferenceAcceptsAtUri() {
		// AT-URI inputs do not need an HTTP round-trip — pass any client.
		val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
		val parsed = AtprotoListResolver.parseListReference(sampleAtUri, client)
		assertEquals(sampleAtUri, parsed)
	}

	@Test
	fun testParseListReferenceAcceptsBskyAppUrlWithDid() {
		val raw = "https://bsky.app/profile/did:plc:abc123/lists/3kxx"
		val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
		val parsed = AtprotoListResolver.parseListReference(raw, client)
		assertEquals(sampleAtUri, parsed)
	}

	@Test
	fun testParseListReferenceResolvesHandleInBskyAppUrl() {
		val resolvedDid = "did:plc:resolvedhandle"
		val client = HttpClient(MockEngine { request ->
			val url = request.url.toString()
			assertTrue(url.contains("resolveHandle"), "expected handle resolution; got $url")
			respond(
				content = """{"did":"$resolvedDid"}""",
				status = HttpStatusCode.OK,
				headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
			)
		}) {
			install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
		}
		val raw = "https://bsky.app/profile/alice.bsky.social/lists/3kxx"
		val parsed = AtprotoListResolver.parseListReference(raw, client)
		assertEquals("at://$resolvedDid/app.bsky.graph.list/3kxx", parsed)
	}

	@Test
	fun testParseListReferenceRejectsEmpty() {
		val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
		assertFailsWith<IllegalArgumentException> {
			AtprotoListResolver.parseListReference("   ", client)
		}
	}

	@Test
	fun testParseListReferenceRejectsAtUriWithoutListPath() {
		val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
		assertFailsWith<IllegalArgumentException> {
			AtprotoListResolver.parseListReference("at://did:plc:abc123/some.other.collection/xyz", client)
		}
	}

	@Test
	fun testParseListReferenceRejectsUnknownScheme() {
		val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
		assertFailsWith<IllegalArgumentException> {
			AtprotoListResolver.parseListReference("ftp://bsky.app/profile/x/lists/y", client)
		}
	}

	@Test
	fun testParseListReferenceRejectsBskyAppUrlWithBadShape() {
		val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
		assertFailsWith<IllegalArgumentException> {
			// missing /lists/ segment
			AtprotoListResolver.parseListReference("https://bsky.app/profile/alice.bsky.social/posts/3kxx", client)
		}
	}
}
