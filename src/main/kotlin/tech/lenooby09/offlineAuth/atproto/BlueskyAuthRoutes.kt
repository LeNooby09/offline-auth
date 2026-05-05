package tech.lenooby09.offlineAuth.atproto

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import tech.lenooby09.offlineAuth.OfflineAuth
import tech.lenooby09.offlineAuth.auth.AuthManager
import tech.lenooby09.offlineAuth.config.OfflineAuthConfig

private val log = LoggerFactory.getLogger("BlueskyAuthRoutes")

/**
 * Pick the OAuth `application_type` advertised in the client metadata based on
 * the redirect URI. The ATProto OAuth provider validators reject `native` for
 * non-loopback HTTPS redirect URIs, so we publish `web` for those and reserve
 * `native` for loopback dev (`http://127.0.0.1`, `http://localhost`,
 * `http://[::1]`). `validateBlueskyConfig` already enforces "HTTPS or loopback
 * only", so these are the only two possible buckets.
 */
internal fun deriveApplicationType(redirectUri: String): String {
	val lower = redirectUri.lowercase()
	val isLoopback = lower.startsWith("http://127.0.0.1") ||
		lower.startsWith("http://localhost") ||
		lower.startsWith("http://[::1]")
	return if (isLoopback) "native" else "web"
}

/**
 * Build the OAuth client metadata document advertised at
 * `/oauth-client-metadata.json`. Extracted so the route can stay a thin
 * controller and so tests can assert the shape of the document directly
 * without spinning up the full ktor application.
 */
internal fun buildClientMetadata(config: OfflineAuthConfig): ClientMetadata =
	ClientMetadata(
		clientId = config.blueskyClientId,
		clientName = config.blueskyClientName,
		clientUri = config.blueskyPublicUrl,
		redirectUris = listOf(config.blueskyRedirectUri),
		grantTypes = listOf("authorization_code", "refresh_token"),
		responseTypes = listOf("code"),
		scope = config.blueskyScope,
		tokenEndpointAuthMethod = "none",
		applicationType = deriveApplicationType(config.blueskyRedirectUri),
		dpopBoundAccessTokens = true,
	)

/**
 * Mounts the Bluesky OAuth Ktor routes on the embedded web server. Only call
 * this when `bluesky-enabled=true` and the config has been validated; in
 * password mode none of these routes should exist.
 */
fun Route.blueskyAuthRoutes(
	config: OfflineAuthConfig,
	atprotoClient: AtprotoOAuthClient,
	listResolver: AtprotoListResolver,
	authManager: AuthManager,
	sessionStore: BlueskySessionStore,
) {
	// Client metadata (served as JSON so Bluesky's auth server can fetch it).
	get("/oauth-client-metadata.json") {
		call.respond(buildClientMetadata(config))
	}

	// Handle entry form (GET) — pairing token bound, browser opens this from chat link.
	get("/bluesky/login/{pairingToken}") {
		val pairingToken = call.parameters["pairingToken"]
		if (pairingToken.isNullOrBlank()) {
			call.respondHtmlPage(HttpStatusCode.BadRequest, errorPage("Missing pairing token."))
			return@get
		}
		if (sessionStore.getPairing(pairingToken) == null) {
			call.respondHtmlPage(
				HttpStatusCode.BadRequest,
				errorPage("This pairing link is invalid or has expired. Run /bluesky in-game again."),
			)
			return@get
		}
		call.respondHtmlPage(HttpStatusCode.OK, handleEntryPage(pairingToken))
	}

	// Handle entry form submission — kicks off PAR.
	post("/bluesky/login/{pairingToken}") {
		val pairingToken = call.parameters["pairingToken"]
		if (pairingToken.isNullOrBlank()) {
			call.respondHtmlPage(HttpStatusCode.BadRequest, errorPage("Missing pairing token."))
			return@post
		}
		val pairing = sessionStore.getPairing(pairingToken)
		if (pairing == null) {
			call.respondHtmlPage(
				HttpStatusCode.BadRequest,
				errorPage("This pairing link is invalid or has expired. Run /bluesky in-game again."),
			)
			return@post
		}

		val params = call.receiveParameters()
		val handle = params["handle"]?.trim()?.removePrefix("@")
		if (handle.isNullOrBlank()) {
			call.respondHtmlPage(
				HttpStatusCode.BadRequest,
				errorPage("Bluesky handle is required."),
			)
			return@post
		}

		val result = try {
			atprotoClient.startAuthorization(handle)
		} catch (e: Exception) {
			log.error("Failed to start ATProto authorization for handle '{}'", handle, e)
			call.respondHtmlPage(
				HttpStatusCode.BadGateway,
				errorPage("Failed to start authorization: ${e.message ?: "unknown error"}"),
			)
			return@post
		}

		// Persist the in-flight state so the callback can reconstitute it.
		sessionStore.storeAtprotoAuth(
			result.atprotoState,
			PendingAtprotoAuth(
				pairingToken = pairingToken,
				codeVerifier = result.codeVerifier,
				dpopKeyPair = result.dpopKeyPair,
				authServerIssuer = result.authServerIssuer,
				tokenEndpoint = result.tokenEndpoint,
				pdsUrl = result.pdsUrl,
				handle = result.handle,
				did = result.did,
			),
		)

		// Render an HTML interstitial that uses an opaque-origin sandboxed iframe to
		// top-navigate the browser to the PDS authorize URL. A plain server-side 302
		// would be tagged Sec-Fetch-Site: same-site whenever the PDS shares a
		// registrable domain with `bluesky-public-url`, and the upstream
		// `@atproto/oauth-provider` rejects that. See `redirectToAuthorizePage(...)`.
		call.response.headers.append("Referrer-Policy", "no-referrer")
		call.respondHtmlPage(HttpStatusCode.OK, redirectToAuthorizePage(result.authorizeUrl))
	}

	// OAuth callback.
	get("/bluesky/callback") {
		val code = call.parameters["code"]
		val state = call.parameters["state"]
		val errorParam = call.parameters["error"]

		if (errorParam != null) {
			val desc = call.parameters["error_description"] ?: errorParam
			call.respondHtmlPage(HttpStatusCode.OK, errorPage("Bluesky authorization failed: $desc"))
			return@get
		}
		if (code.isNullOrBlank() || state.isNullOrBlank()) {
			call.respondHtmlPage(HttpStatusCode.BadRequest, errorPage("Missing code or state parameter."))
			return@get
		}

		val pending = sessionStore.consumeAtprotoAuth(state)
		if (pending == null) {
			call.respondHtmlPage(
				HttpStatusCode.BadRequest,
				errorPage("Unknown or expired authorization state. Please run /bluesky again."),
			)
			return@get
		}

		// Issuer match — ATProto auth servers include the iss in the callback.
		val callbackIss = call.parameters["iss"]
		if (callbackIss != null && callbackIss != pending.authServerIssuer) {
			log.warn(
				"ATProto callback issuer mismatch: expected '{}' got '{}'",
				pending.authServerIssuer,
				callbackIss,
			)
			call.respondHtmlPage(
				HttpStatusCode.BadRequest,
				errorPage("Issuer mismatch. The authorization response was not signed by the expected server."),
			)
			return@get
		}

		val pairing = sessionStore.consumePairing(pending.pairingToken)
		if (pairing == null) {
			call.respondHtmlPage(
				HttpStatusCode.BadRequest,
				errorPage("In-game pairing has already been consumed or expired. Run /bluesky again."),
			)
			return@get
		}

		val tokenResp = try {
			atprotoClient.exchangeCode(
				code = code,
				codeVerifier = pending.codeVerifier,
				dpopKeyPair = pending.dpopKeyPair,
				tokenEndpoint = pending.tokenEndpoint,
			)
		} catch (e: Exception) {
			log.error("ATProto code exchange failed for state '{}'", state, e)
			call.respondHtmlPage(
				HttpStatusCode.BadGateway,
				errorPage("Failed to exchange the authorization code: ${e.message ?: "unknown error"}"),
			)
			return@get
		}

		// Granted scope must include `atproto`.
		if (!AtprotoOAuthClient.isScopeGranted(tokenResp.scope, "atproto")) {
			call.respondHtmlPage(
				HttpStatusCode.Forbidden,
				errorPage("The required 'atproto' scope was not granted."),
			)
			return@get
		}

		// Re-resolve to confirm the DID returned matches the one we initiated for.
		val did = tokenResp.sub.takeIf { it.isNotBlank() } ?: pending.did

		// Whitelist check — refresh-on-miss is built into AtprotoListResolver.
		val isAllowed = try {
			listResolver.isMember(did)
		} catch (e: Exception) {
			log.error("Failed to verify Bluesky list membership for did '{}'", did, e)
			call.respondHtmlPage(
				HttpStatusCode.BadGateway,
				errorPage("Could not verify whitelist membership: ${e.message ?: "unknown error"}"),
			)
			return@get
		}
		if (!isAllowed) {
			OfflineAuth.LOGGER.info("[Bluesky] Rejecting login for did={} — not on whitelist list.", did)
			call.respondHtmlPage(
				HttpStatusCode.Forbidden,
				errorPage("You're not on the operator's whitelist for this server."),
			)
			return@get
		}

		// Best-effort profile fetch for avatar (failures are non-fatal).
		val avatar = try {
			atprotoClient.getProfile(
				accessToken = tokenResp.accessToken,
				dpopKeyPair = pending.dpopKeyPair,
				pdsUrl = pending.pdsUrl,
				did = did,
			).avatar
		} catch (e: Exception) {
			log.warn("getProfile failed for did='{}': {}", did, e.message)
			null
		}

		// Hand off to the AuthManager — the actual onAuthenticated runs on the server tick thread.
		try {
			authManager.handleBlueskyLogin(
				playerUuid = pairing.playerUuid,
				did = did,
				handle = pending.handle,
				avatar = avatar,
			)
		} catch (e: Exception) {
			log.error("handleBlueskyLogin failed for did='{}'", did, e)
			call.respondHtmlPage(
				HttpStatusCode.InternalServerError,
				errorPage("Authentication succeeded with Bluesky, but failed to finalize in-game login: ${e.message ?: "unknown error"}"),
			)
			return@get
		}

		call.respondHtmlPage(HttpStatusCode.OK, successPage(pending.handle))
	}
}

// ---------- HTML helpers ----------

private suspend fun ApplicationCall.respondHtmlPage(status: HttpStatusCode, html: String) {
	respondText(html, ContentType.Text.Html, status)
}

private fun handleEntryPage(pairingToken: String): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Sign in with Bluesky</title>
<style>
*, *::before, *::after { box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
display: flex; justify-content: center; align-items: center; min-height: 100vh;
margin: 0; background: #111111; color: #cccccc; }
.box { background: #161616; padding: 2rem; border: 1px solid #333333; max-width: 400px; width: 100%; }
h1 { color: #58a6ff; font-size: 1.5rem; text-align: center; margin-top: 0; }
label { display: block; margin: 1rem 0 0.5rem; font-weight: 600; }
input[type="text"] { width: 100%; padding: 0.75rem; border: 1px solid #333333;
background: #222222; color: #cccccc; font-size: 1rem; }
input[type="text"]:focus { border-color: #58a6ff; outline: none; }
button { width: 100%; padding: 0.75rem; background: #58a6ff; color: #111111;
border: none; font-size: 1rem; font-weight: 600; cursor: pointer; margin-top: 1rem; }
button:hover { background: rgb(78, 156, 245); }
.hint { color: #888888; font-size: 0.85rem; margin-top: 0.5rem; }
</style>
</head>
<body>
<div class="box">
<h1>Sign in with Bluesky</h1>
<form method="POST" action="/bluesky/login/${escapeHtml(pairingToken)}">
<label for="handle">Bluesky handle</label>
<input type="text" id="handle" name="handle" placeholder="alice.bsky.social" autofocus required />
<div class="hint">Your full handle without the leading <code>@</code>.</div>
<button type="submit">Continue</button>
</form>
</div>
</body>
</html>"""

private fun successPage(handle: String): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Authenticated</title>
<style>
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
display: flex; justify-content: center; align-items: center; min-height: 100vh;
margin: 0; background: #111111; color: #cccccc; }
.box { background: #161616; padding: 2rem; border: 1px solid #333333; max-width: 480px; width: 100%; }
h1 { color: #4ade80; font-size: 1.5rem; text-align: center; margin-top: 0; }
p { line-height: 1.6; }
</style>
</head>
<body>
<div class="box">
<h1>You're signed in</h1>
<p>Welcome, <strong>${escapeHtml(handle)}</strong>. You can return to Minecraft — your session has been authenticated automatically.</p>
</div>
</body>
</html>"""

private fun errorPage(message: String): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Authentication Error</title>
<style>
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
display: flex; justify-content: center; align-items: center; min-height: 100vh;
margin: 0; background: #111111; color: #cccccc; }
.box { background: #161616; padding: 2rem; border: 1px solid #333333; max-width: 480px; width: 100%; }
h1 { color: #f38ba8; font-size: 1.5rem; text-align: center; margin-top: 0; }
p { line-height: 1.6; }
</style>
</head>
<body>
<div class="box">
<h1>Authentication Error</h1>
<p>${escapeHtml(message)}</p>
</div>
</body>
</html>"""

/**
 * Render an HTML interstitial that uses an opaque-origin sandboxed iframe to
 * top-navigate the browser to [authorizeUrl] instead of issuing a server-side
 * `302`. The iframe's `sandbox="allow-scripts allow-top-navigation"` (note: no
 * `allow-same-origin`) makes its origin opaque, so the browser tags the
 * resulting top-level navigation as `Sec-Fetch-Site: cross-site` instead of
 * `same-site`. This bypasses the `@atproto/oauth-provider` authorize-endpoint
 * guard at `packages/oauth/oauth-provider/src/router/create-authorization-page-middleware.ts`,
 * which only accepts `same-origin`, `cross-site`, `none` — and therefore lets
 * OfflineAuth be hosted on a sibling subdomain of the player's PDS (e.g.
 * `atpcraft.lenooby09.tech` ↔ `pds.lenooby09.tech`).
 *
 * The visible fallback `<a rel="noreferrer">` link covers users with
 * JavaScript disabled or strict iframe-blocking extensions; clicking a
 * `noreferrer` link is also a top-level navigation that the browser tags as
 * `cross-site` (no source origin to compare with).
 *
 * Ported from the sibling `intermediate-oauth` project's
 * `src/main/kotlin/tech/lenooby09/provider/OAuthProvider.kt` lines 286–360
 * (the `post("/authorize/login")` handler).
 */
internal fun redirectToAuthorizePage(authorizeUrl: String): String {
	val js = authorizeUrl
		.replace("\\", "\\\\")
		.replace("\"", "\\\"")
		.replace("'", "\\'")
		.replace("<", "\\x3c")
		.replace(">", "\\x3e")
	return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Redirecting to Bluesky</title>
<style>
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
display: flex; justify-content: center; align-items: center; min-height: 100vh;
margin: 0; background: #111111; color: #cccccc; }
.box { background: #161616; padding: 2rem; border: 1px solid #333333; max-width: 480px; width: 100%; text-align: center; }
p { line-height: 1.6; }
a { color: #58a6ff; text-decoration: none; font-weight: 600; }
a:hover { text-decoration: underline; }
</style>
</head>
<body>
<div class="box">
<p>Redirecting to Bluesky…</p>
<p><a id="redir-link" href="${escapeHtml(authorizeUrl)}" rel="noreferrer">Click here if not redirected</a></p>
</div>
<script>
(function() {
    var url = "$js";
    var iframe = document.createElement('iframe');
    iframe.style.display = 'none';
    iframe.sandbox = 'allow-scripts allow-top-navigation';
    iframe.srcdoc = '\x3chtml\x3e\x3chead\x3e\x3cscript\x3ewindow.top.location.href = ' + JSON.stringify(url) + ';\x3c/script\x3e\x3c/head\x3e\x3cbody\x3e\x3c/body\x3e\x3c/html\x3e';
    document.body.appendChild(iframe);
})();
</script>
</body>
</html>"""
}

private fun escapeHtml(s: String): String = s
	.replace("&", "&amp;")
	.replace("<", "&lt;")
	.replace(">", "&gt;")
	.replace("\"", "&quot;")
	.replace("'", "&#39;")
