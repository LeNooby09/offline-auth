package tech.lenooby09.offlineAuth.atproto

import com.nimbusds.jwt.SignedJWT
import kotlin.test.Test
import kotlin.test.assertEquals

class DpopTest {

	@Test
	fun testHtuStripping() {
		val keyPair = DPoP.generateKeyPair()
		val proof = DPoP.createProof(
			keyPair = keyPair,
			httpMethod = "POST",
			targetUri = "https://example.com/token?query=param#fragment"
		)

		val jwt = SignedJWT.parse(proof)
		val claims = jwt.jwtClaimsSet
		assertEquals("https://example.com/token", claims.getClaim("htu"))
	}

	@Test
	fun testHtuWithCustomPort() {
		val keyPair = DPoP.generateKeyPair()
		val proof = DPoP.createProof(
			keyPair = keyPair,
			httpMethod = "GET",
			targetUri = "https://example.com:8443/api"
		)

		val jwt = SignedJWT.parse(proof)
		val claims = jwt.jwtClaimsSet
		assertEquals("https://example.com:8443/api", claims.getClaim("htu"))
	}

	@Test
	fun testHtuWithDefaultPort() {
		val keyPair = DPoP.generateKeyPair()
		val proof = DPoP.createProof(
			keyPair = keyPair,
			httpMethod = "GET",
			targetUri = "https://example.com:443/api"
		)

		val jwt = SignedJWT.parse(proof)
		val claims = jwt.jwtClaimsSet
		assertEquals("https://example.com/api", claims.getClaim("htu"))
	}
}
