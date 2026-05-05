package tech.lenooby09.offlineAuth.atproto

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

object Resolution {

	suspend fun resolveHandle(client: HttpClient, handle: String): String {
		val response = client.get("https://bsky.social/xrpc/com.atproto.identity.resolveHandle") {
			parameter("handle", handle)
		}
		if (!response.status.isSuccess()) {
			throw IllegalStateException("Failed to resolve handle '$handle': ${response.status}")
		}
		return response.body<HandleResolution>().did
	}

	suspend fun resolveDidDocument(client: HttpClient, did: String): DidDocument {
		val url = if (did.startsWith("did:plc:")) {
			"https://plc.directory/$did"
		} else if (did.startsWith("did:web:")) {
			val domain = did.removePrefix("did:web:")
			"https://$domain/.well-known/did.json"
		} else {
			throw IllegalArgumentException("Unsupported DID method: $did")
		}

		val response = client.get(url)
		if (!response.status.isSuccess()) {
			throw IllegalStateException("Failed to resolve DID document for '$did': ${response.status}")
		}
		return response.body<DidDocument>()
	}

	fun getPdsEndpoint(didDoc: DidDocument): String {
		return didDoc.service
			.firstOrNull { it.id == "#atproto_pds" && it.type == "AtprotoPersonalDataServer" }
			?.serviceEndpoint
			?: throw IllegalStateException("No PDS endpoint found in DID document for ${didDoc.id}")
	}

	fun getHandle(didDoc: DidDocument): String {
		return didDoc.alsoKnownAs
			.firstOrNull { it.startsWith("at://") }
			?.removePrefix("at://")
			?: didDoc.id
	}

	suspend fun getPdsMetadata(client: HttpClient, pdsUrl: String): PdsMetadata {
		val response = client.get("$pdsUrl/.well-known/oauth-protected-resource")
		if (!response.status.isSuccess()) {
			throw IllegalStateException("Failed to fetch PDS metadata from '$pdsUrl': ${response.status}")
		}
		return response.body<PdsMetadata>()
	}

	suspend fun getAuthServerMetadata(client: HttpClient, authServerUrl: String): AuthServerMetadata {
		val response = client.get("$authServerUrl/.well-known/oauth-authorization-server")
		if (!response.status.isSuccess()) {
			throw IllegalStateException("Failed to fetch auth server metadata from '$authServerUrl': ${response.status}")
		}
		return response.body<AuthServerMetadata>()
	}
}
