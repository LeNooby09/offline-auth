package tech.lenooby09.offlineAuth.atproto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientMetadata(
	@SerialName("client_id") val clientId: String,
	@SerialName("client_name") val clientName: String,
	@SerialName("client_uri") val clientUri: String,
	@SerialName("redirect_uris") val redirectUris: List<String>,
	@SerialName("grant_types") val grantTypes: List<String>,
	@SerialName("response_types") val responseTypes: List<String>,
	@SerialName("scope") val scope: String,
	@SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
	@SerialName("application_type") val applicationType: String,
	@SerialName("dpop_bound_access_tokens") val dpopBoundAccessTokens: Boolean,
)

@Serializable
data class PdsMetadata(
	@SerialName("authorization_servers") val authorizationServers: List<String>,
)

@Serializable
data class AuthServerMetadata(
	@SerialName("issuer") val issuer: String,
	@SerialName("authorization_endpoint") val authorizationEndpoint: String,
	@SerialName("token_endpoint") val tokenEndpoint: String,
	@SerialName("pushed_authorization_request_endpoint") val parEndpoint: String,
	@SerialName("dpop_signing_alg_values_supported") val dpopSigningAlgValues: List<String> = emptyList(),
	@SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
)

@Serializable
data class ParResponse(
	@SerialName("request_uri") val requestUri: String,
	@SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class AtprotoTokenResponse(
	@SerialName("access_token") val accessToken: String,
	@SerialName("token_type") val tokenType: String,
	@SerialName("refresh_token") val refreshToken: String? = null,
	@SerialName("expires_in") val expiresIn: Int? = null,
	@SerialName("sub") val sub: String,
	@SerialName("scope") val scope: String? = null,
)

@Serializable
data class AtprotoProfileResponse(
	@SerialName("did") val did: String,
	@SerialName("handle") val handle: String,
	@SerialName("displayName") val displayName: String? = null,
	@SerialName("avatar") val avatar: String? = null,
)

@Serializable
data class DidDocument(
	@SerialName("id") val id: String,
	@SerialName("alsoKnownAs") val alsoKnownAs: List<String> = emptyList(),
	@SerialName("service") val service: List<DidService> = emptyList(),
)

@Serializable
data class DidService(
	@SerialName("id") val id: String,
	@SerialName("type") val type: String,
	@SerialName("serviceEndpoint") val serviceEndpoint: String,
)

@Serializable
data class HandleResolution(
	val did: String,
)

// app.bsky.graph.getList response shape (subset).
@Serializable
data class ListGetResponse(
	val items: List<ListItemView> = emptyList(),
	val cursor: String? = null,
)

@Serializable
data class ListItemView(
	val subject: ListSubject,
)

@Serializable
data class ListSubject(
	val did: String,
	val handle: String? = null,
)
