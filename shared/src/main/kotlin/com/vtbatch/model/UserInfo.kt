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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Serializable
data class VTUserInfoResponse(
    val data: VTUserData? = null
)

@Serializable
data class VTUserData(
    val id: String? = null,
    val type: String? = null,
    val attributes: VTUserAttributes? = null
)

@Serializable
data class VTUserAttributes(
    val quotas: VTQuotas? = null
)

@Serializable
data class VTQuotas(
    @SerialName("api_requests_daily") val apiRequestsDaily: VTQuotaInfo? = null,
    @SerialName("api_requests_monthly") val apiRequestsMonthly: VTQuotaInfo? = null
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
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        client.use {
            val response = it.get("${config.apiBaseUrl}/users/$user") {
                header("x-apikey", apiKey)
            }
            if (response.status == HttpStatusCode.OK) response.body<VTUserInfoResponse>()
            else {
                logger.warn { "getUserInfo non-200: ${response.status}" }
                null
            }
        }
    } catch (e: Exception) {
        logger.error { "Error getting user info: ${e.javaClass.simpleName}: ${e.message}" }
        null
    }
}
