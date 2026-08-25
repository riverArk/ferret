package io.riverark.ferret.core.backup

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GoogleDriveAppDataClient(
    private val http: HttpClient,
    private val tokens: OAuthTokenProvider,
    private val json: Json = Json { ignoreUnknownKeys = false; explicitNulls = false },
) : DriveAppDataClient {
    override suspend fun list(prefix: String): List<DriveObject> = files(prefix).map { DriveObject(it.name, 0) }

    override suspend fun put(name: String, bytes: ByteArray) {
        validateName(name)
        require(bytes.size in 1..MAX_BYTES)
        val existing = files(name).singleOrNull { it.name == name }
        if (existing == null) {
            val metadata = json.encodeToString(DriveMetadata(name, listOf("appDataFolder")))
            http.post("$UPLOAD/files") {
                parameter("uploadType", "multipart")
                bearerAuth(tokens.accessToken())
                setBody(MultiPartFormDataContent(formData {
                    append("metadata", metadata, io.ktor.http.Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) })
                    append("file", bytes, io.ktor.http.Headers.build { append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()) })
                }))
            }
        } else {
            http.patch("$UPLOAD/files/${existing.id}") {
                parameter("uploadType", "media")
                bearerAuth(tokens.accessToken())
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
        }
    }

    override suspend fun get(name: String): ByteArray {
        val file = files(name).single { it.name == name }
        return http.get("$API/files/${file.id}") {
            parameter("alt", "media")
            bearerAuth(tokens.accessToken())
        }.body<ByteArray>().also { require(it.size <= MAX_BYTES) { "backup response too large" } }
    }

    override suspend fun delete(name: String) {
        files(name).filter { it.name == name }.forEach { file ->
            http.delete("$API/files/${file.id}") { bearerAuth(tokens.accessToken()) }
        }
    }

    private suspend fun files(prefix: String): List<DriveFile> {
        validateName(prefix)
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val page = http.get("$API/files") {
                bearerAuth(tokens.accessToken())
                parameter("spaces", "appDataFolder")
                parameter("pageSize", "1000")
                parameter("fields", "nextPageToken,files(id,name,modifiedTime)")
                parameter("q", "'appDataFolder' in parents and trashed = false and name contains '$prefix'")
                pageToken?.let { parameter("pageToken", it) }
            }.body<DriveFilesPage>()
            result += page.files.filter { it.name.startsWith(prefix) }
            require(result.size <= 10_000) { "too many Drive backup files" }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    private fun validateName(value: String) {
        require(value.length in 1..160 && value.all { it.isLetterOrDigit() || it in "-_." })
    }

    @Serializable private data class DriveMetadata(val name: String, val parents: List<String>)
    @Serializable private data class DriveFilesPage(val files: List<DriveFile>, val nextPageToken: String? = null)
    @Serializable private data class DriveFile(val id: String, val name: String, val modifiedTime: String? = null) {
        init {
            require(id.length in 1..256 && name.length in 1..160)
            require(modifiedTime == null || modifiedTime.length in 1..64)
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val MAX_BYTES = 1_048_576
    }
}
