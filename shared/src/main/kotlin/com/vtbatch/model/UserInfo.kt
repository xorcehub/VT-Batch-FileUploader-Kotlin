package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

@Serializable
data class VTUserInfoResponse(
    val data: VTUserData? = null
)

@Serializable
data class VTUserData(
    val attributes: VTUserAttributes? = null
)

@Serializable
data class VTUserAttributes(
    val quotas: VTQuotas? = null
)

@Serializable
data class VTQuotas(
    val api_requests_daily: VTQuotaInfo? = null,
    val api_requests_monthly: VTQuotaInfo? = null
)

@Serializable
data class VTQuotaInfo(
    val used: Int = 0,
    val allowed: Int = 500
)

/**
 * Fetches user info and quota from VirusTotal API.
 * Matches Python's user_info.py.
 */
suspend fun getUserInfo(apiKey: String, user: String, config: AppConfig = AppConfig.default, engine: HttpClientEngine = OkHttp.create()): VTUserInfoResponse? {
    return try {
        val client = HttpClient(engine) {
            install(HttpTimeout) { requestTimeoutMillis = config.shortTimeout * 1000L }
            install(ContentNegotiation) { json() }
        }
        client.use {
            val response = it.get("${config.apiBaseUrl}/users/$user") {
                header("x-apikey", apiKey)
            }
            if (response.status == HttpStatusCode.OK) response.body<VTUserInfoResponse>()
            else null
        }
    } catch (e: Exception) {
        logger.error { "Error getting user info: $e" }
        null
    }
}
