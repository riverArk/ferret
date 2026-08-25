package io.riverark.ferret.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpCallValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.contentLength
import kotlinx.serialization.json.Json

internal const val MAX_RESPONSE_BYTES = 1_048_576L
internal val ferretJson = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false }

fun ferretHttpClient(engine: HttpClientEngine) = HttpClient(engine) {
    followRedirects = false
    expectSuccess = true
    install(HttpCallValidator) {
        validateResponse { response ->
            require(response.contentLength()?.let { it <= MAX_RESPONSE_BYTES } != false) { "response too large" }
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
    install(ContentNegotiation) {
        json(ferretJson)
    }
}
