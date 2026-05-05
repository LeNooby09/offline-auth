package tech.lenooby09.offlineAuth.atproto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in the structural elements of the HTML interstitial returned by
 * [redirectToAuthorizePage]. The interstitial is the workaround for the
 * upstream `@atproto/oauth-provider` `Sec-Fetch-Site: same-site` rejection
 * (see `packages/oauth/oauth-provider/src/router/create-authorization-page-middleware.ts`):
 *
 * - The iframe MUST sandbox itself to `allow-scripts allow-top-navigation`
 *   without `allow-same-origin`. Granting `allow-same-origin` would un-opaque
 *   the iframe's origin and put us back at `Sec-Fetch-Site: same-site`.
 * - The fallback `<a>` link MUST carry `rel="noreferrer"` so that a click
 *   navigation also produces `cross-site` (no source origin → `cross-site`).
 * - The URL placed inside the inline `<script>` MUST be JS-escaped so that
 *   pathological query-string characters (`"`, `'`, `<`, `>`, `\`) cannot
 *   break out of the JS string literal. Inside the visible fallback `<a href="…">`
 *   the URL must be HTML-escaped instead.
 *
 * These invariants are easy to break with a careless edit (e.g. adding
 * `allow-same-origin` "for parity with the parent page", or forgetting the
 * `\x3c`/`\x3e` JS escapes), so they are guarded explicitly here.
 */
class BlueskyAuthRedirectPageTest {

	@Test
	fun typicalAuthorizeUrlContainsAllRequiredElements() {
		val authorizeUrl =
			"https://pds.lenooby09.tech/oauth/authorize" +
				"?request_uri=urn%3Aietf%3Aparams%3Aoauth%3Arequest_uri%3Areq-abc" +
				"&client_id=https%3A%2F%2Fatpcraft.lenooby09.tech%2Foauth-client-metadata.json%3Fv%3D7698b38f"
		val html = redirectToAuthorizePage(authorizeUrl)

		assertTrue(
			html.contains("iframe.sandbox = 'allow-scripts allow-top-navigation'"),
			"Interstitial must set the iframe sandbox to exactly 'allow-scripts allow-top-navigation'",
		)
		assertFalse(
			html.contains("allow-same-origin"),
			"Interstitial must NOT contain 'allow-same-origin' — that would un-opaque the iframe's origin and re-trigger the same-site rejection.",
		)
		assertTrue(
			html.contains("rel=\"noreferrer\""),
			"Fallback link must carry rel=\"noreferrer\" so a manual click is also tagged cross-site.",
		)

		// Inside the inline <script>, the URL is dropped verbatim into a JS string
		// literal — no HTML escaping. The literal `&` and `?` must stay intact so
		// JSON.stringify(url) at runtime forwards the same query string we built.
		assertTrue(
			html.contains("var url = \"$authorizeUrl\";"),
			"The URL inside the JS string literal should be present unmodified (no HTML escaping inside <script>).",
		)

		// Inside the visible fallback <a href="…">, the URL is HTML-escaped — `&`
		// becomes `&amp;` so the markup parses correctly.
		val htmlEscapedHref = authorizeUrl.replace("&", "&amp;")
		assertTrue(
			html.contains("href=\"$htmlEscapedHref\""),
			"The fallback <a href='…'> must HTML-escape the URL (`&` → `&amp;`); was missing in:\n$html",
		)

		// The JS srcdoc template uses backslash-x escapes so the outer HTML parser
		// does not prematurely close the surrounding <script>.
		assertTrue(
			html.contains("'\\x3chtml\\x3e\\x3chead\\x3e\\x3cscript\\x3e"),
			"srcdoc must use \\x3c/\\x3e escapes to avoid the outer parser closing the script early.",
		)
		assertTrue(
			html.contains("\\x3c/script\\x3e\\x3c/head\\x3e\\x3cbody\\x3e\\x3c/body\\x3e\\x3c/html\\x3e"),
			"srcdoc must end with a complete \\x3c/script\\x3e\\x3c/html\\x3e tail in escaped form.",
		)
	}

	@Test
	fun dangerousCharactersInUrlAreJsEscaped() {
		// Crafted URL exercises every character the JS-escape pipeline must handle:
		//   `"`, `'`, `\`, `<`, `>`. If any of these survive unescaped into the inline
		// `<script>`, an attacker could break out of the JS string literal or close
		// the surrounding `<script>` tag.
		val nasty = """https://pds.example/x?q="</script>&y='&z=\back"""
		val html = redirectToAuthorizePage(nasty)

		// Verify each dangerous character was JS-escaped inside the inline <script>.
		assertTrue(
			html.contains("var url = \"https://pds.example/x?q=\\\"\\x3c/script\\x3e&y=\\'&z=\\\\back\";"),
			"All of \" ' \\ < > must be JS-escaped; produced HTML:\n$html",
		)

		// The dangerous characters that the HTML parser cares about (`<`, `>`, `&`,
		// `"`, `'`) must also be HTML-escaped inside the fallback `<a href="…">`.
		assertTrue(
			html.contains("href=\"https://pds.example/x?q=&quot;&lt;/script&gt;&amp;y=&#39;&amp;z=\\back\""),
			"Fallback href must HTML-escape \" < > & ' (single backslash is left as-is in HTML attributes); produced HTML:\n$html",
		)

		// And — most importantly — there must be exactly ONE literal `</script>` in
		// the response body (the outer wrapping tag). Any more would mean the URL
		// payload smuggled in a tag that breaks out of the surrounding `<script>`,
		// causing the HTML parser to terminate the script early and expose the rest
		// of the JS as page text. The counterpart `<script>` count must also be
		// exactly one for the same reason.
		assertEquals(
			1,
			html.split("</script>").size - 1,
			"Response body must contain exactly one literal </script>; any extra would mean URL injection broke out of the wrapping script element.",
		)
		assertEquals(
			1,
			html.split("<script>").size - 1,
			"Response body must contain exactly one literal <script>; any extra would mean URL injection opened a nested script element.",
		)
	}

	@Test
	fun emptyAuthorizeUrlDoesNotCrash() {
		// Defensive: PAR success guarantees a non-empty URL in production, but the
		// helper itself should be a pure string transform that never throws.
		val html = redirectToAuthorizePage("")
		assertTrue(
			html.contains("var url = \"\";"),
			"Empty URL must produce an empty JS string literal, not throw.",
		)
		assertTrue(
			html.contains("rel=\"noreferrer\""),
			"Even with an empty URL, structural attributes must still be present.",
		)
	}
}
