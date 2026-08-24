package io.riverark.ferret.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun ferretHttpClient(engine: HttpClientEngine) = HttpClient(engine) {
    followRedirects = false
    expectSuccess = true
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false })
    }
}
