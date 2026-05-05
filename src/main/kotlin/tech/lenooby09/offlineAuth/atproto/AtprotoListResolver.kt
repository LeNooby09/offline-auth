package tech.lenooby09.offlineAuth.atproto

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Resolves a Bluesky list reference (either an `at://...app.bsky.graph.list/...`
 * URI or a `https://bsky.app/profile/<handle-or-did>/lists/<rkey>` URL) into a
 * cached set of member DIDs by paginating `app.bsky.graph.getList` against
 * `https://public.api.bsky.app`.
 *
 * The cache honours [cacheTtlSeconds]; callers should use [isMember] which
 * automatically refreshes on miss before a final reject.
 */
class AtprotoListResolver(
	private val httpClient: HttpClient,
	rawRef: String,
	private val cacheTtlSeconds: Long,
	private val publicApiBase: String = "https://public.api.bsky.app",
) {
	private val log = LoggerFactory.getLogger(AtprotoListResolver::class.java)

	val atUri: String = parseListReferenceBlocking(rawRef)

	@Volatile
	private var cached: Set<String> = emptySet()

	@Volatile
	private var cachedAt: Long = 0

	private val refreshMutex = Mutex()

	/**
	 * Returns `true` if the given DID is a member of the configured list. If
	 * the cache says no but is older than [cacheTtlSeconds] (or empty), one
	 * refresh is attempted before issuing a final reject.
	 */
	suspend fun isMember(did: String): Boolean {
		val now = System.currentTimeMillis()
		val cacheAgeSec = (now - cachedAt) / 1000

		// Fresh-cache hit fast path.
		if (cacheAgeSec < cacheTtlSeconds && cached.contains(did)) {
			return true
		}

		// Refresh if cache is stale or the DID is missing.
		if (cacheAgeSec >= cacheTtlSeconds || !cached.contains(did)) {
			refresh()
		}
		return cached.contains(did)
	}

	/**
	 * Returns the full cached DID set, refreshing first if the cache is stale.
	 */
	suspend fun getMembers(force: Boolean = false): Set<String> {
		val now = System.currentTimeMillis()
		val cacheAgeSec = (now - cachedAt) / 1000
		if (force || cacheAgeSec >= cacheTtlSeconds || cached.isEmpty()) {
			refresh()
		}
		return cached
	}

	/**
	 * Force a refresh of the cached member set, paginating until the server
	 * stops returning a cursor. Capped at 200 pages defensively (= 20 000
	 * members at limit=100).
	 */
	suspend fun refresh(): Set<String> = refreshMutex.withLock {
		val members = mutableSetOf<String>()
		var cursor: String? = null
		var pages = 0
		val maxPages = 200

		do {
			val response = httpClient.get("$publicApiBase/xrpc/app.bsky.graph.getList") {
				parameter("list", atUri)
				parameter("limit", 100)
				if (cursor != null) parameter("cursor", cursor)
			}
			if (!response.status.isSuccess()) {
				throw IllegalStateException(
					"Failed to fetch list members for '$atUri': ${response.status}"
				)
			}
			val page = response.body<ListGetResponse>()
			page.items.forEach { members.add(it.subject.did) }
			cursor = page.cursor
			pages++
		} while (!cursor.isNullOrBlank() && pages < maxPages)

		if (pages >= maxPages && !cursor.isNullOrBlank()) {
			log.warn(
				"Bluesky list pagination cap reached ($maxPages pages) for {}; truncating membership at {} entries",
				atUri,
				members.size,
			)
		}

		cached = members.toSet()
		cachedAt = System.currentTimeMillis()
		log.info("Refreshed Bluesky list '{}': {} members", atUri, cached.size)
		cached
	}

	private fun parseListReferenceBlocking(raw: String): String = parseListReference(raw, httpClient)

	companion object {
		/**
		 * Accepts either an AT-URI of the form
		 * `at://did:.../app.bsky.graph.list/<rkey>` or a bsky.app URL of the
		 * form `https://bsky.app/profile/<handle-or-did>/lists/<rkey>`.
		 *
		 * If the bsky.app URL contains a handle, this method will resolve it
		 * to a DID by calling [Resolution.resolveHandle] via the supplied
		 * [httpClient]. The returned string is always a canonical AT-URI.
		 *
		 * For the constructor we want a non-suspending call site, so this
		 * helper performs the (rare) handle-resolution synchronously by
		 * blocking the caller via `runBlocking`.
		 */
		fun parseListReference(raw: String, httpClient: HttpClient): String {
			val trimmed = raw.trim()
			if (trimmed.isEmpty()) {
				throw IllegalArgumentException("Bluesky list reference is empty")
			}

			if (trimmed.startsWith("at://")) {
				if (!trimmed.contains("/app.bsky.graph.list/")) {
					throw IllegalArgumentException(
						"Bluesky list AT-URI must contain '/app.bsky.graph.list/' (got: $trimmed)"
					)
				}
				return trimmed
			}

			if (trimmed.startsWith("https://bsky.app/") || trimmed.startsWith("http://bsky.app/")) {
				val url = Url(trimmed)
				val segments = url.segments.filter { it.isNotBlank() }
				// Expected: ["profile", "<handle-or-did>", "lists", "<rkey>"]
				if (segments.size < 4 || segments[0] != "profile" || segments[2] != "lists") {
					throw IllegalArgumentException(
						"Bluesky list URL must look like https://bsky.app/profile/<handle-or-did>/lists/<rkey>: $trimmed"
					)
				}
				val identifier = segments[1]
				val rkey = segments[3]
				val did = if (identifier.startsWith("did:")) {
					identifier
				} else {
					kotlinx.coroutines.runBlocking { Resolution.resolveHandle(httpClient, identifier) }
				}
				return "at://$did/app.bsky.graph.list/$rkey"
			}

			throw IllegalArgumentException(
				"Bluesky list reference must be an at:// URI or a https://bsky.app/.../lists/... URL (got: $trimmed)"
			)
		}
	}
}
