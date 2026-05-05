package tech.lenooby09.offlineAuth.atproto

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.*
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.*

object DPoP {

	fun generateKeyPair(): KeyPair {
		val ecKey = ECKeyGenerator(Curve.P_256).generate()
		return KeyPair(ecKey.toECPublicKey(), ecKey.toECPrivateKey())
	}

	fun createProof(
		keyPair: KeyPair,
		httpMethod: String,
		targetUri: String,
		nonce: String? = null,
		accessTokenHash: String? = null,
	): String {
		val ecPublicKey = keyPair.public as ECPublicKey
		val ecPrivateKey = keyPair.private as ECPrivateKey

		val ecKey = ECKey.Builder(Curve.P_256, ecPublicKey)
			.privateKey(ecPrivateKey)
			.build()

		val headerBuilder = JWSHeader.Builder(JWSAlgorithm.ES256)
			.type(JOSEObjectType("dpop+jwt"))
			.jwk(ecKey.toPublicJWK())

		val url = Url(targetUri)
		val portSuffix = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
		val htu = "${url.protocol.name}://${url.host}$portSuffix${url.encodedPath}"

		val claimsBuilder = JWTClaimsSet.Builder()
			.jwtID(UUID.randomUUID().toString())
			.claim("htm", httpMethod)
			.claim("htu", htu)
			.issueTime(Date())

		if (nonce != null) {
			claimsBuilder.claim("nonce", nonce)
		}
		if (accessTokenHash != null) {
			claimsBuilder.claim("ath", accessTokenHash)
		}

		val signedJwt = SignedJWT(headerBuilder.build(), claimsBuilder.build())
		signedJwt.sign(ECDSASigner(ecKey))
		return signedJwt.serialize()
	}

	fun getThumbprint(keyPair: KeyPair): String {
		val ecPublicKey = keyPair.public as ECPublicKey
		val ecKey = ECKey.Builder(Curve.P_256, ecPublicKey).build()
		return ecKey.computeThumbprint().toString()
	}
}
