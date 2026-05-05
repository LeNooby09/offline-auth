package tech.lenooby09.offlineAuth.atproto

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/**
 * Stateless ATProto OAuth client. The PAR + DPoP + token-exchange logic is
 * ported from `intermediate-oauth/.../AtprotoOAuthClient.kt`, with the
 * Forgejo-specific glue (`getSession`, the `SessionStore` writes, the
 * forgejo-state plumbing) stripped out — `startAuthorization` returns a
 * [StartAuthorizationResult] that callers store however they like.
 */
class AtprotoOAuthClient(
	private val httpClient: HttpClient,
	private val clientId: String,
	private val redirectUri: String,
	private val scope: String,
) {
	private val log = LoggerFactory.getLogger(AtprotoOAuthClient::class.java)

	/**
	 * Result of a successful PAR submission. Callers must persist these fields
	 * against the generated [atprotoState] so the callback handler can recover
	 * the DPoP key pair, code verifier, and target token endpoint.
	 */
	data class StartAuthorizationResult(
		val authorizeUrl: String,
		val atprotoState: String,
		val codeVerifier: String,
		val dpopKeyPair: KeyPair,
		val authServerIssuer: String,
		val tokenEndpoint: String,
		val pdsUrl: String,
		val did: String,
		val handle: String,
	)

	suspend fun startAuthorization(handle: String): StartAuthorizationResult {
		log.info("Starting ATProto OAuth for handle: {}", handle)

		val did = Resolution.resolveHandle(httpClient, handle)
		log.info("Resolved handle '{}' to DID: {}", handle, did)

		val didDoc = Resolution.resolveDidDocument(httpClient, did)
		val pdsUrl = Resolution.getPdsEndpoint(didDoc)
		log.info("PDS endpoint: {}", pdsUrl)

		val pdsMetadata = Resolution.getPdsMetadata(httpClient, pdsUrl)
		val authServerUrl = pdsMetadata.authorizationServers.firstOrNull()
			?: throw IllegalStateException("No authorization server found for PDS: $pdsUrl")
		log.info("Authorization server: {}", authServerUrl)

		val authServerMetadata = Resolution.getAuthServerMetadata(httpClient, authServerUrl)

		val codeVerifier = generateCodeVerifier()
		val codeChallenge = generateCodeChallenge(codeVerifier)
		val atprotoState = generateRandomString(32)
		val dpopKeyPair = DPoP.generateKeyPair()

		val authorizeUrl = submitPar(
			authServerMetadata = authServerMetadata,
			codeChallenge = codeChallenge,
			state = atprotoState,
			dpopKeyPair = dpopKeyPair,
			loginHint = handle,
		)

		log.info("Authorize URL prepared for handle '{}'", handle)

		return StartAuthorizationResult(
			authorizeUrl = authorizeUrl,
			atprotoState = atprotoState,
			codeVerifier = codeVerifier,
			dpopKeyPair = dpopKeyPair,
			authServerIssuer = authServerMetadata.issuer,
			tokenEndpoint = authServerMetadata.tokenEndpoint,
			pdsUrl = pdsUrl,
			did = did,
			handle = handle,
		)
	}

	private suspend fun submitPar(
		authServerMetadata: AuthServerMetadata,
		codeChallenge: String,
		state: String,
		dpopKeyPair: KeyPair,
		loginHint: String,
	): String {
		val parEndpoint = authServerMetadata.parEndpoint
		val dpopJkt = DPoP.getThumbprint(dpopKeyPair)

		val response = sendWithDpopNonceRetry(
			dpopKeyPair = dpopKeyPair,
			method = "POST",
			url = parEndpoint,
			retryLogMessage = "Retrying PAR with DPoP nonce",
		) { dpopProof ->
			httpClient.submitForm(
				url = parEndpoint,
				formParameters = parameters {
					append("response_type", "code")
					append("code_challenge", codeChallenge)
					append("code_challenge_method", "S256")
					append("client_id", clientId)
					append("state", state)
					append("redirect_uri", redirectUri)
					append("scope", scope)
					append("login_hint", loginHint)
					append("dpop_jkt", dpopJkt)
				},
			) {
				header("DPoP", dpopProof)
			}
		}

		if (!response.status.isSuccess()) {
			val body = response.bodyAsText()
			throw IllegalStateException("PAR request failed (${response.status}): $body")
		}

		val parResponse = response.body<ParResponse>()
		return "${authServerMetadata.authorizationEndpoint}?" +
				"request_uri=${parResponse.requestUri.encodeURLParameter()}" +
				"&client_id=${clientId.encodeURLParameter()}"
	}

	suspend fun exchangeCode(
		code: String,
		codeVerifier: String,
		dpopKeyPair: KeyPair,
		tokenEndpoint: String,
	): AtprotoTokenResponse {
		val response = sendWithDpopNonceRetry(
			dpopKeyPair = dpopKeyPair,
			method = "POST",
			url = tokenEndpoint,
			retryLogMessage = "Retrying token exchange with DPoP nonce",
		) { dpopProof ->
			httpClient.submitForm(
				url = tokenEndpoint,
				formParameters = parameters {
					append("grant_type", "authorization_code")
					append("code", code)
					append("redirect_uri", redirectUri)
					append("client_id", clientId)
					append("code_verifier", codeVerifier)
				},
			) {
				header("DPoP", dpopProof)
			}
		}

		if (!response.status.isSuccess()) {
			val body = response.bodyAsText()
			throw IllegalStateException("Token exchange failed (${response.status}): $body")
		}

		return response.body<AtprotoTokenResponse>()
	}

	suspend fun getProfile(
		accessToken: String,
		dpopKeyPair: KeyPair,
		pdsUrl: String,
		did: String,
	): AtprotoProfileResponse {
		val url = "${pdsUrl.trimEnd('/')}/xrpc/app.bsky.actor.getProfile"
		val ath = computeAccessTokenHash(accessToken)

		val response = sendWithDpopNonceRetry(
			dpopKeyPair = dpopKeyPair,
			method = "GET",
			url = url,
			accessTokenHash = ath,
			retryLogMessage = "Retrying getProfile with DPoP nonce",
		) { dpopProof ->
			httpClient.get(url) {
				parameter("actor", did)
				header(HttpHeaders.Authorization, "DPoP $accessToken")
				header("DPoP", dpopProof)
				header("Atproto-Proxy", "did:web:api.bsky.app#bsky_appview")
			}
		}

		if (!response.status.isSuccess()) {
			val body = response.bodyAsText()
			throw IllegalStateException("getProfile request failed (${response.status}): $body")
		}

		return response.body<AtprotoProfileResponse>()
	}

	/**
	 * Send a DPoP-protected request and retry up to 2 times when the server
	 * responds with 400/401 plus a `DPoP-Nonce` header. Each retry rebuilds the
	 * DPoP proof using the freshly returned nonce. RFC 9449 allows the server
	 * to rotate the nonce on every response, so a single retry is not always
	 * sufficient — this helper hardens the flow against mid-flight nonce
	 * rotation while keeping the success path identical (a single request when
	 * no nonce challenge is issued).
	 */
	private suspend fun sendWithDpopNonceRetry(
		dpopKeyPair: KeyPair,
		method: String,
		url: String,
		accessTokenHash: String? = null,
		retryLogMessage: String,
		sendRequest: suspend (dpopProof: String) -> HttpResponse,
	): HttpResponse {
		val initialProof = DPoP.createProof(
			dpopKeyPair,
			method,
			url,
			accessTokenHash = accessTokenHash,
		)
		var response = sendRequest(initialProof)

		for (attempt in 0 until 2) {
			if (response.status != HttpStatusCode.BadRequest &&
				response.status != HttpStatusCode.Unauthorized
			) {
				break
			}
			val dpopNonce = response.headers["DPoP-Nonce"] ?: break
			log.info(retryLogMessage)
			val retryProof = DPoP.createProof(
				dpopKeyPair,
				method,
				url,
				nonce = dpopNonce,
				accessTokenHash = accessTokenHash,
			)
			response = sendRequest(retryProof)
		}

		return response
	}

	companion object {
		private val secureRandom = SecureRandom()

		fun generateCodeVerifier(): String {
			val bytes = ByteArray(32)
			secureRandom.nextBytes(bytes)
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
		}

		fun generateCodeChallenge(verifier: String): String {
			val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
		}

		fun generateRandomString(length: Int): String {
			val bytes = ByteArray(length)
			secureRandom.nextBytes(bytes)
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
		}

		// RFC 9449 §6.1: ath = base64url(no-padding) of SHA-256(ASCII(access_token))
		fun computeAccessTokenHash(accessToken: String): String {
			val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII))
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
		}

		// Returns true iff the AT Protocol OAuth granted-scope string explicitly contains the required scope token.
		// AT Protocol OAuth servers MUST return the granted scope in the token response; a null/blank value is treated as no grant.
		fun isScopeGranted(grantedScope: String?, requiredScope: String): Boolean {
			val granted = grantedScope?.split(" ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
			return granted.contains(requiredScope)
		}
	}
}
