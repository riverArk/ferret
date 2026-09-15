import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.net.ssl.HttpsURLConnection
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
}


private data class CatalogEntry(
    val alias: String,
    val policyId: String?,
    val assetName: String?,
    val decimals: Int,
    val pricing: String,
)

private data class Presentation(
    val alias: String,
    val policyId: String,
    val assetName: String,
    val fingerprint: String,
    val name: String,
    val ticker: String,
    val description: String,
    val url: String,
    val logoPath: String,
    val logoHash: String,
    val logoBytes: ByteArray,
)

private data class GeneratedMetadata(val manifest: ByteArray, val entries: List<Presentation>)

private class EmbeddedAssetMetadata {
    companion object {
private val CATALOG_LIMIT = 64 * 1024
private val MANIFEST_LIMIT = 64 * 1024
private val DISCOVERY_LIMIT = 64 * 1024
private val KOIOS_LIMIT = 2 * 1024 * 1024
private val REQUEST_LIMIT = 1024
private val PNG_LIMIT = 262_144
private val MAX_CATALOG_ENTRIES = 16
private val KOIOS_URL = "https://api.koios.rest/api/v1/asset_info"
private val DISCOVERY_URL = "https://konduit.crustypants.com/info"
private val HEX_64 = Regex("[0-9a-f]{64}")
private val POLICY = Regex("[0-9a-f]{56}")
private val ASSET_NAME = Regex("(?:[0-9a-f]{2}){0,32}")
private val FINGERPRINT = Regex("asset1[023456789acdefghjklmnpqrstuvwxyz]{38}")
private val TICKER = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    fun fetchDiscovery(): ByteArray = get(DISCOVERY_URL, DISCOVERY_LIMIT)
    fun fetchKoios(entries: List<CatalogEntry>): ByteArray = post(KOIOS_URL, koiosRequest(entries), KOIOS_LIMIT)

    fun readBounded(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        input.readNBytes(limit + 1).also { require(it.size <= limit) { "${file.name} exceeds $limit bytes" } }
    }

    private fun parse(bytes: ByteArray, context: String): Any {
        val text = bytes.toString(StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) { "$context is not valid UTF-8" }
        return try {
            JsonSlurper().parseText(text)
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed $context", error)
        }
    }

    private fun exactMap(value: Any?, fields: Set<String>, context: String): Map<String, Any?> {
        require(value is Map<*, *>) { "$context must be an object" }
        require(value.keys.all { it is String }) { "$context has a non-string property" }
        @Suppress("UNCHECKED_CAST")
        val map = value as Map<String, Any?>
        require(map.keys == fields) { "$context properties must be ${fields.sorted()}" }
        return map
    }

    private fun objectMap(value: Any?, context: String): Map<String, Any?> {
        require(value is Map<*, *>) { "$context must be an object" }
        require(value.keys.all { it is String }) { "$context has a non-string property" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun array(value: Any?, context: String): List<Any?> {
        require(value is List<*>) { "$context must be an array" }
        return value
    }

    private fun string(value: Any?, context: String): String {
        require(value is String) { "$context must be a string" }
        return value
    }

    private fun integer(value: Any?, context: String): Int {
        require(value is Byte || value is Short || value is Int || value is Long || value is java.math.BigInteger) {
            "$context must be an integer"
        }
        val number = value.toString().toLongOrNull()
        require(number != null && number in Int.MIN_VALUE..Int.MAX_VALUE) { "$context is out of range" }
        return number.toInt()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun readSidecar(file: File): String {
        val bytes = readBounded(file, 65)
        val value = bytes.toString(StandardCharsets.US_ASCII)
        require(value.length == 65 && value.endsWith('\n') && HEX_64.matches(value.dropLast(1))) {
            "${file.name} must contain one lowercase SHA-256 and LF"
        }
        return value.dropLast(1)
    }

    fun catalog(catalogFile: File, digestFile: File): List<CatalogEntry> {
        val bytes = readBounded(catalogFile, CATALOG_LIMIT)
        require(sha256(bytes) == readSidecar(digestFile)) { "Catalog digest mismatch" }
        val root = objectMap(parse(bytes, "catalog"), "catalog")
        require(root.size <= MAX_CATALOG_ENTRIES) { "Catalog exceeds $MAX_CATALOG_ENTRIES entries" }
        require(root.keys.toList() == root.keys.sorted()) { "Catalog aliases must be sorted" }
        val identities = mutableSetOf<Pair<String, String>>()
        val entries = root.map { (key, raw) ->
            val definition = exactMap(raw, setOf("alias", "asset", "decimals", "pricing"), "catalog.$key")
            val alias = string(definition["alias"], "catalog.$key.alias")
            require(key == alias && alias.isNotEmpty()) { "Catalog key and alias must match" }
            val decimals = integer(definition["decimals"], "catalog.$key.decimals")
            require(decimals in 0..19) { "catalog.$key.decimals must be 0..19" }
            val asset = objectMap(definition["asset"], "catalog.$key.asset")
            val kind = string(asset["kind"], "catalog.$key.asset.kind")
            val policyId: String?
            val assetName: String?
            when (kind) {
                "ada" -> {
                    require(asset.keys == setOf("kind")) { "ADA asset has unknown properties" }
                    policyId = null
                    assetName = null
                }
                "native" -> {
                    require(asset.keys == setOf("kind", "policy_id", "asset_name")) { "Native asset has unknown properties" }
                    policyId = string(asset["policy_id"], "catalog.$key.asset.policy_id")
                    assetName = string(asset["asset_name"], "catalog.$key.asset.asset_name")
                    require(POLICY.matches(policyId)) { "Invalid policy ID for $key" }
                    require(ASSET_NAME.matches(assetName)) { "Invalid asset name for $key" }
                    require(identities.add(policyId to assetName)) { "Duplicate catalog identity" }
                }
                else -> error("Unsupported asset kind $kind")
            }
            val pricingObject = exactMap(definition["pricing"], setOf("kind"), "catalog.$key.pricing")
            val pricing = string(pricingObject["kind"], "catalog.$key.pricing.kind")
            require(pricing == if (kind == "ada") "ada" else "usd_peg") { "Unsupported pricing for $key" }
            CatalogEntry(alias, policyId, assetName, decimals, pricing)
        }
        require(entries.map { it.alias }.containsAll(listOf("ada", "usda", "usdcx", "usdm"))) { "Catalog is missing built-ins" }
        require(entries.single { it.alias == "ada" }.policyId == null) { "ADA definition is invalid" }
        require(renderCatalog(entries).contentEquals(bytes)) { "Catalog is not canonical" }
        return entries
    }

    private fun renderCatalog(entries: List<CatalogEntry>): ByteArray {
        val root = linkedMapOf<String, Any>()
        entries.sortedBy { it.alias }.forEach { entry ->
            val asset = if (entry.policyId == null) linkedMapOf("kind" to "ada") else linkedMapOf(
                "kind" to "native",
                "policy_id" to entry.policyId,
                "asset_name" to entry.assetName,
            )
            root[entry.alias] = linkedMapOf(
                "alias" to entry.alias,
                "asset" to asset,
                "decimals" to entry.decimals,
                "pricing" to linkedMapOf("kind" to entry.pricing),
            )
        }
        return JsonOutput.toJson(root).toByteArray(StandardCharsets.UTF_8)
    }

    private fun sanitized(raw: Any?, context: String, required: Boolean, maxBytes: Int): String {
        val value = when (raw) {
            null -> ""
            is String -> raw
            else -> error("$context must be a string or null")
        }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 8 * 1024) { "$context exceeds raw text limit" }
        var index = 0
        while (index < value.length) {
            val char = value[index]
            require(!Character.isSurrogate(char) || (Character.isHighSurrogate(char) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))) {
                "$context contains an invalid surrogate"
            }
            index += if (Character.isHighSurrogate(char)) 2 else 1
        }
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        val output = StringBuilder()
        var pendingSpace = false
        normalized.codePoints().forEach { codePoint ->
            when {
                Character.isWhitespace(codePoint) -> pendingSpace = output.isNotEmpty()
                Character.getType(codePoint) == Character.CONTROL.toInt() || Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
                else -> {
                    if (pendingSpace) output.append(' ')
                    output.appendCodePoint(codePoint)
                    pendingSpace = false
                }
            }
        }
        val result = output.toString()
        val size = result.toByteArray(StandardCharsets.UTF_8).size
        require((!required || result.isNotEmpty()) && size <= maxBytes) { "$context has invalid length" }
        return result
    }

    private fun validateTicker(raw: Any?, context: String): String {
        val ticker = sanitized(raw, context, required = true, maxBytes = 16)
        require(TICKER.matches(ticker)) { "$context is invalid" }
        return ticker
    }

    private fun validateUrl(raw: Any?, context: String): String {
        val value = sanitized(raw, context, required = false, maxBytes = 512)
        if (value.isNotEmpty()) {
            val uri = try { URI(value) } catch (error: Exception) { throw IllegalArgumentException("$context is invalid", error) }
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.userInfo == null) {
                "$context must be an absolute credential-free HTTPS URL"
            }
        }
        return value
    }

    fun validatePng(bytes: ByteArray, context: String): Pair<Int, Int> {
        require(bytes.size in 1..PNG_LIMIT) { "$context PNG size is invalid" }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(signature)) { "$context is not a PNG" }
        var offset = 8
        var first = true
        var sawIdat = false
        var sawIend = false
        var width = 0
        var height = 0
        while (offset < bytes.size) {
            require(bytes.size - offset >= 12) { "$context has a truncated PNG chunk" }
            val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            require(length >= 0 && length <= PNG_LIMIT && length <= bytes.size - offset - 12) { "$context has an invalid PNG chunk length" }
            val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
            val type = typeBytes.toString(StandardCharsets.US_ASCII)
            require(typeBytes.all { (it.toInt() and 0xff) in 65..90 || (it.toInt() and 0xff) in 97..122 }) { "$context has an invalid PNG chunk type" }
            val dataStart = offset + 8
            val crcOffset = dataStart + length
            val expectedCrc = ByteBuffer.wrap(bytes, crcOffset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val crc = CRC32().apply { update(typeBytes); update(bytes, dataStart, length) }.value
            require(crc == expectedCrc) { "$context has an invalid PNG CRC" }
            if (first) {
                require(type == "IHDR" && length == 13) { "$context PNG must start with IHDR" }
                width = ByteBuffer.wrap(bytes, dataStart, 4).order(ByteOrder.BIG_ENDIAN).int
                height = ByteBuffer.wrap(bytes, dataStart + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                require(width in 1..1024 && height in 1..1024 && width.toLong() * height <= 1_048_576L) { "$context PNG dimensions are invalid" }
                require(bytes[dataStart + 8].toInt() and 0xff in setOf(1, 2, 4, 8, 16)) { "$context PNG bit depth is invalid" }
                require(bytes[dataStart + 10].toInt() == 0 && bytes[dataStart + 11].toInt() == 0 && bytes[dataStart + 12].toInt() == 0) { "$context PNG methods are unsupported" }
                first = false
            } else {
                require(type != "IHDR") { "$context has duplicate IHDR" }
            }
            if (type == "IDAT") sawIdat = true
            if (type == "IEND") {
                require(length == 0 && sawIdat) { "$context has an invalid IEND" }
                sawIend = true
                offset = crcOffset + 4
                require(offset == bytes.size) { "$context has bytes after IEND" }
                break
            }
            if (typeBytes[0].toInt() and 0x20 == 0) require(type in setOf("IHDR", "PLTE", "IDAT", "IEND")) { "$context has unsupported critical chunk $type" }
            offset = crcOffset + 4
        }
        require(sawIend) { "$context PNG is missing IEND" }
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            require(readers.hasNext()) { "$context PNG has no ImageIO decoder" }
            val reader = readers.next()
            try {
                reader.input = stream
                require(reader.getWidth(0) == width && reader.getHeight(0) == height) { "$context PNG dimensions disagree" }
                val image = reader.read(0)
                require(image.width == width && image.height == height) { "$context PNG failed to decode" }
            } finally {
                reader.dispose()
            }
        }
        return width to height
    }

    private fun nativeCatalog(entries: List<CatalogEntry>) = entries.filter { it.policyId != null }
        .sortedWith(compareBy({ it.policyId }, { it.assetName }))

    fun koiosRequest(entries: List<CatalogEntry>): ByteArray {
        val list = nativeCatalog(entries).map { listOf(it.policyId, it.assetName) }
        val bytes = JsonOutput.toJson(linkedMapOf("_asset_list" to list)).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= REQUEST_LIMIT) { "Koios request exceeds $REQUEST_LIMIT bytes" }
        return bytes
    }

    fun generate(entries: List<CatalogEntry>, responseBytes: ByteArray): GeneratedMetadata {
        require(responseBytes.size <= KOIOS_LIMIT) { "Koios response exceeds $KOIOS_LIMIT bytes" }
        val requested = nativeCatalog(entries)
        val byIdentity = requested.associateBy { it.policyId!! to it.assetName!! }
        val seen = mutableSetOf<Pair<String, String>>()
        val presentations = array(parse(responseBytes, "Koios response"), "Koios response").mapIndexed { index, raw ->
            val row = objectMap(raw, "Koios response[$index]")
            val policyId = string(row["policy_id"], "Koios response[$index].policy_id")
            val assetName = string(row["asset_name"], "Koios response[$index].asset_name")
            val identity = policyId to assetName
            val catalog = requireNotNull(byIdentity[identity]) { "Koios returned unknown asset $policyId.$assetName" }
            require(seen.add(identity)) { "Koios returned duplicate asset $policyId.$assetName" }
            val fingerprint = string(row["fingerprint"], "Koios response[$index].fingerprint")
            require(FINGERPRINT.matches(fingerprint)) { "Invalid fingerprint for ${catalog.alias}" }
            val registry = objectMap(row["token_registry_metadata"], "Koios response[$index].token_registry_metadata")
            require(integer(registry["decimals"], "${catalog.alias}.decimals") == catalog.decimals) { "Registry decimals differ for ${catalog.alias}" }
            val name = sanitized(registry["name"], "${catalog.alias}.name", required = true, maxBytes = 128)
            val ticker = validateTicker(registry["ticker"], "${catalog.alias}.ticker")
            val description = sanitized(registry["description"], "${catalog.alias}.description", required = false, maxBytes = 1024)
            val url = validateUrl(registry["url"], "${catalog.alias}.url")
            val logoText = string(registry["logo"], "${catalog.alias}.logo")
            require(logoText.toByteArray(StandardCharsets.US_ASCII).size <= ((PNG_LIMIT + 2) / 3) * 4) { "${catalog.alias}.logo is too large" }
            val logo = try { Base64.getDecoder().decode(logoText) } catch (error: Exception) { throw IllegalArgumentException("${catalog.alias}.logo is invalid base64", error) }
            validatePng(logo, catalog.alias)
            val logoPath = "drawable/asset_${policyId}_${assetName}.png"
            Presentation(catalog.alias, policyId, assetName, fingerprint, name, ticker, description, url, logoPath, sha256(logo), logo)
        }.sortedWith(compareBy({ it.policyId }, { it.assetName }))
        require(seen == byIdentity.keys) { "Koios response is missing ${byIdentity.keys - seen}" }
        require(presentations.map { it.ticker.lowercase(Locale.ROOT) }.toSet().size == presentations.size) { "Token tickers must be unique ignoring case" }
        return GeneratedMetadata(ByteArray(0), presentations)
    }

    private fun renderManifest(entries: List<Presentation>, catalogDigest: String?): ByteArray {
        val digest = catalogDigest ?: error("Catalog digest required")
        val root = linkedMapOf<String, Any>(
            "schema" to 1,
            "source" to linkedMapOf("url" to KOIOS_URL, "api_version" to "v1", "spec_version" to "v1.4.2"),
            "catalog_digest" to digest,
            "entries" to entries.map { entry -> linkedMapOf(
                "alias" to entry.alias,
                "policy_id" to entry.policyId,
                "asset_name" to entry.assetName,
                "fingerprint" to entry.fingerprint,
                "name" to entry.name,
                "ticker" to entry.ticker,
                "description" to entry.description,
                "url" to entry.url,
                "logo" to entry.logoPath,
                "logo_sha256" to entry.logoHash,
            ) },
        )
        return (JsonOutput.prettyPrint(JsonOutput.toJson(root)) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    fun withCatalogDigest(generated: GeneratedMetadata, digest: String): GeneratedMetadata = generated.copy(
        manifest = renderManifest(generated.entries, digest),
    )

    fun verify(resources: File, catalogFile: File, catalogDigestFile: File) {
        val catalog = catalog(catalogFile, catalogDigestFile)
        val catalogDigest = readSidecar(catalogDigestFile)
        val metadata = File(resources, "files/asset-metadata")
        val manifestFile = File(metadata, "manifest.json")
        val manifestBytes = readBounded(manifestFile, MANIFEST_LIMIT)
        require(sha256(manifestBytes) == readSidecar(File(metadata, "manifest.sha256"))) { "Manifest digest mismatch" }
        val root = exactMap(parse(manifestBytes, "manifest"), setOf("schema", "source", "catalog_digest", "entries"), "manifest")
        require(integer(root["schema"], "manifest.schema") == 1) { "Unsupported manifest schema" }
        val source = exactMap(root["source"], setOf("url", "api_version", "spec_version"), "manifest.source")
        require(source == mapOf("url" to KOIOS_URL, "api_version" to "v1", "spec_version" to "v1.4.2")) { "Unexpected manifest source" }
        require(string(root["catalog_digest"], "manifest.catalog_digest") == catalogDigest) { "Manifest catalog digest mismatch" }
        val expected = nativeCatalog(catalog).associateBy { it.policyId!! to it.assetName!! }
        val seen = mutableSetOf<Pair<String, String>>()
        val tickers = mutableSetOf<String>()
        val entries = array(root["entries"], "manifest.entries").mapIndexed { index, raw ->
            val row = exactMap(raw, setOf("alias", "policy_id", "asset_name", "fingerprint", "name", "ticker", "description", "url", "logo", "logo_sha256"), "manifest.entries[$index]")
            val policyId = string(row["policy_id"], "manifest.entries[$index].policy_id")
            val assetName = string(row["asset_name"], "manifest.entries[$index].asset_name")
            val identity = policyId to assetName
            val definition = requireNotNull(expected[identity]) { "Manifest contains unknown asset" }
            require(seen.add(identity)) { "Manifest has duplicate identity" }
            val alias = string(row["alias"], "manifest.entries[$index].alias")
            require(alias == definition.alias) { "Manifest alias does not match catalog" }
            val fingerprint = string(row["fingerprint"], "$alias.fingerprint")
            require(FINGERPRINT.matches(fingerprint)) { "$alias fingerprint is invalid" }
            val name = sanitized(row["name"], "$alias.name", required = true, maxBytes = 128)
            require(name == row["name"]) { "$alias name is not normalized" }
            val ticker = validateTicker(row["ticker"], "$alias.ticker")
            require(tickers.add(ticker.lowercase(Locale.ROOT))) { "Manifest tickers are not unique" }
            val description = sanitized(row["description"], "$alias.description", required = false, maxBytes = 1024)
            require(description == row["description"]) { "$alias description is not normalized" }
            val url = validateUrl(row["url"], "$alias.url")
            require(url == row["url"]) { "$alias URL is not normalized" }
            val expectedPath = "drawable/asset_${policyId}_${assetName}.png"
            val logoPath = string(row["logo"], "$alias.logo")
            require(logoPath == expectedPath && !File(logoPath).isAbsolute && !logoPath.split('/').contains("..")) { "$alias logo path is invalid" }
            val logoFile = File(resources, logoPath)
            require(!Files.isSymbolicLink(logoFile.toPath()) && logoFile.isFile) { "$alias logo is missing or symbolic" }
            val logoBytes = readBounded(logoFile, PNG_LIMIT)
            validatePng(logoBytes, alias)
            val logoHash = string(row["logo_sha256"], "$alias.logo_sha256")
            require(HEX_64.matches(logoHash) && sha256(logoBytes) == logoHash) { "$alias logo digest mismatch" }
            Presentation(alias, policyId, assetName, fingerprint, name, ticker, description, url, logoPath, logoHash, logoBytes)
        }
        require(seen == expected.keys && seen.containsAll(listOf("usdm", "usdcx").map { alias -> catalog.single { it.alias == alias }.let { it.policyId!! to it.assetName!! } })) { "Manifest is missing pinned native assets" }
        require(entries.map { it.policyId to it.assetName } == entries.map { it.policyId to it.assetName }.sortedWith(compareBy({ it.first }, { it.second }))) { "Manifest entries must be sorted" }
        require(renderManifest(entries, catalogDigest).contentEquals(manifestBytes)) { "Manifest is not canonical" }
        val referenced = entries.map { File(resources, it.logoPath).canonicalFile }.toSet()
        val actual = File(resources, "drawable").listFiles { file -> file.name.startsWith("asset_") && file.name.endsWith(".png") }?.map { it.canonicalFile }?.toSet().orEmpty()
        require(actual == referenced) { "Unreferenced or missing asset logo files: actual=$actual expected=$referenced" }
    }

    fun get(url: String, limit: Int): ByteArray = request(url, "GET", null, limit)

    fun post(url: String, body: ByteArray, limit: Int): ByteArray = request(url, "POST", body, limit)

    private fun request(url: String, method: String, body: ByteArray?, limit: Int): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                require(body.size <= REQUEST_LIMIT) { "Request body exceeds $REQUEST_LIMIT bytes" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val response = stream?.use { it.readNBytes(limit + 1) } ?: ByteArray(0)
            require(response.size <= limit) { "$url response exceeds $limit bytes" }
            require(status == 200) { "$url returned HTTP $status" }
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun discoveryDigest(bytes: ByteArray): String {
        require(bytes.size <= DISCOVERY_LIMIT) { "Discovery response exceeds $DISCOVERY_LIMIT bytes" }
        val root = objectMap(parse(bytes, "discovery response"), "discovery response")
        val digest = string(root["asset_catalog_digest"], "discovery.asset_catalog_digest")
        require(HEX_64.matches(digest)) { "Discovery catalog digest is invalid" }
        return digest
    }

    fun publish(resources: File, generated: GeneratedMetadata) {
        val staging = Files.createTempDirectory(resources.toPath(), ".asset-metadata-").toFile()
        try {
            generated.entries.forEach { entry ->
                File(staging, entry.logoPath).apply { parentFile.mkdirs(); writeBytes(entry.logoBytes) }
            }
            val stagedMetadata = File(staging, "files/asset-metadata").apply { mkdirs() }
            File(stagedMetadata, "manifest.json").writeBytes(generated.manifest)
            File(stagedMetadata, "manifest.sha256").writeText(sha256(generated.manifest) + "\n", StandardCharsets.US_ASCII)
            generated.entries.forEach { entry -> validatePng(File(staging, entry.logoPath).readBytes(), entry.alias) }
            val oldOwned = runCatching { ownedLogoPaths(File(resources, "files/asset-metadata/manifest.json")) }.getOrDefault(emptySet())
            generated.entries.forEach { entry -> atomicReplace(File(staging, entry.logoPath), File(resources, entry.logoPath)) }
            atomicReplace(File(stagedMetadata, "manifest.json"), File(resources, "files/asset-metadata/manifest.json"))
            atomicReplace(File(stagedMetadata, "manifest.sha256"), File(resources, "files/asset-metadata/manifest.sha256"))
            val newOwned = generated.entries.map { it.logoPath }.toSet()
            (oldOwned - newOwned).forEach { path -> File(resources, path).takeIf { it.isFile }?.delete() }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun ownedLogoPaths(manifestFile: File): Set<String> {
        if (!manifestFile.isFile) return emptySet()
        val root = objectMap(parse(readBounded(manifestFile, MANIFEST_LIMIT), "old manifest"), "old manifest")
        return array(root["entries"], "old manifest.entries").map { row -> string(objectMap(row, "old entry")["logo"], "old entry.logo") }.filter { it.startsWith("drawable/asset_") && !it.contains("..") }.toSet()
    }

    private fun atomicReplace(source: File, destination: File) {
        destination.parentFile.mkdirs()
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun regression(resources: File, catalogFile: File, catalogDigestFile: File) {
        verify(resources, catalogFile, catalogDigestFile)
        val catalog = catalog(catalogFile, catalogDigestFile)
        val digest = readSidecar(catalogDigestFile)
        val manifestRoot = objectMap(parse(readBounded(File(resources, "files/asset-metadata/manifest.json"), MANIFEST_LIMIT), "manifest"), "manifest")
        val rows = array(manifestRoot["entries"], "manifest.entries").map { raw ->
            val entry = objectMap(raw, "manifest entry")
            val logo = File(resources, string(entry["logo"], "logo")).readBytes()
            linkedMapOf<String, Any?>(
                "policy_id" to entry["policy_id"],
                "asset_name" to entry["asset_name"],
                "fingerprint" to entry["fingerprint"],
                "total_supply" to "ignored",
                "mint_cnt" to 999,
                "token_registry_metadata" to linkedMapOf(
                    "name" to entry["name"], "ticker" to entry["ticker"], "description" to entry["description"],
                    "url" to entry["url"], "logo" to Base64.getEncoder().encodeToString(logo), "decimals" to catalog.single { it.alias == entry["alias"] }.decimals,
                ),
            )
        }
        fun generated(items: List<Map<String, Any?>>): GeneratedMetadata = withCatalogDigest(generate(catalog, JsonOutput.toJson(items).toByteArray()), digest)
        val forward = generated(rows)
        val reversed = generated(rows.reversed().map { LinkedHashMap(it).apply { this["total_supply"] = "changed"; this["mint_cnt"] = 0 } })
        check(forward.manifest.contentEquals(reversed.manifest) && forward.entries.zip(reversed.entries).all { (a, b) -> a.logoBytes.contentEquals(b.logoBytes) }) { "Generation is not deterministic" }
        val usdm = forward.entries.single { it.alias == "usdm" }
        check(validatePng(usdm.logoBytes, "usdm") == 938 to 938) { "USDM 938px logo regression" }

        val sentinel = Files.createTempDirectory("ferret-metadata-generation-").toFile()
        File(sentinel, "unchanged").writeText("valid")
        fun expectGenerationFailure(label: String, mutate: (MutableList<MutableMap<String, Any?>>) -> Unit) {
            val copy: MutableList<MutableMap<String, Any?>> = rows.map { row ->
                LinkedHashMap<String, Any?>(row).also { cloned ->
                    cloned["token_registry_metadata"] = LinkedHashMap(objectMap(row["token_registry_metadata"], "registry"))
                }
            }.toMutableList()
            mutate(copy)
            check(runCatching { generated(copy) }.isFailure) { "$label was accepted" }
            check(File(sentinel, "unchanged").readText() == "valid") { "$label changed existing output" }
        }
        expectGenerationFailure("mismatched identity") { it[0]["policy_id"] = "0".repeat(56) }
        expectGenerationFailure("duplicate response") { it.add(LinkedHashMap(it[0])) }
        expectGenerationFailure("missing response") { it.removeAt(0) }
        expectGenerationFailure("extra response") { it.add(LinkedHashMap(it[0]).apply { this["policy_id"] = "0".repeat(56) }) }
        expectGenerationFailure("wrong decimals") { objectMap(it[0]["token_registry_metadata"], "registry").let { registry -> (registry as MutableMap<String, Any?>)["decimals"] = 5 } }
        expectGenerationFailure("missing registry") { it[0]["token_registry_metadata"] = null }
        expectGenerationFailure("missing logo") { objectMap(it[0]["token_registry_metadata"], "registry").let { registry -> (registry as MutableMap<String, Any?>)["logo"] = null } }
        expectGenerationFailure("malformed base64") { objectMap(it[0]["token_registry_metadata"], "registry").let { registry -> (registry as MutableMap<String, Any?>)["logo"] = "!" } }
        expectGenerationFailure("invalid PNG CRC") { objectMap(it[0]["token_registry_metadata"], "registry").let { registry ->
            val bytes = Base64.getDecoder().decode(string(registry["logo"], "logo")); bytes[bytes.lastIndex - 5] = (bytes[bytes.lastIndex - 5].toInt() xor 1).toByte(); (registry as MutableMap<String, Any?>)["logo"] = Base64.getEncoder().encodeToString(bytes)
        } }
        expectGenerationFailure("truncated PNG") { objectMap(it[0]["token_registry_metadata"], "registry").let { registry ->
            val bytes = Base64.getDecoder().decode(string(registry["logo"], "logo")); (registry as MutableMap<String, Any?>)["logo"] = Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 1))
        } }
        val wide = BufferedImage(1025, 1, BufferedImage.TYPE_INT_ARGB)
        val wideBytes = java.io.ByteArrayOutputStream().also { ImageIO.write(wide, "png", it) }.toByteArray()
        expectGenerationFailure("over-width PNG") { objectMap(it[0]["token_registry_metadata"], "registry").let { registry -> (registry as MutableMap<String, Any?>)["logo"] = Base64.getEncoder().encodeToString(wideBytes) } }
        sentinel.deleteRecursively()

        fun copyResources(): File {
            val target = Files.createTempDirectory("ferret-metadata-verify-").toFile()
            resources.copyRecursively(target, overwrite = true)
            return target
        }
        fun rewriteManifest(root: MutableMap<String, Any?>, target: File) {
            val bytes = (JsonOutput.prettyPrint(JsonOutput.toJson(root)) + "\n").toByteArray()
            File(target, "files/asset-metadata/manifest.json").writeBytes(bytes)
            File(target, "files/asset-metadata/manifest.sha256").writeText(sha256(bytes) + "\n")
        }
        fun expectVerifyFailure(label: String, mutate: (File) -> Unit) {
            val target = copyResources()
            try {
                mutate(target)
                check(runCatching { verify(target, File(target, "files/asset-metadata/catalog.json"), File(target, "files/asset-metadata/catalog.sha256")) }.isFailure) { "$label was accepted" }
            } finally { target.deleteRecursively() }
        }
        expectVerifyFailure("modified catalog") { File(it, "files/asset-metadata/catalog.json").appendText(" ") }
        expectVerifyFailure("alias mismatch") { target ->
            val root = objectMap(parse(File(target, "files/asset-metadata/manifest.json").readBytes(), "manifest"), "manifest").toMutableMap()
            val entries = array(root["entries"], "entries").map { LinkedHashMap(objectMap(it, "entry")) }; entries[0]["alias"] = "wrong"; root["entries"] = entries; rewriteManifest(root, target)
        }
        expectVerifyFailure("identity mismatch") { target ->
            val root = objectMap(parse(File(target, "files/asset-metadata/manifest.json").readBytes(), "manifest"), "manifest").toMutableMap()
            val entries = array(root["entries"], "entries").map { LinkedHashMap(objectMap(it, "entry")) }; entries[0]["policy_id"] = "0".repeat(56); root["entries"] = entries; rewriteManifest(root, target)
        }
        expectVerifyFailure("missing USDCx") { target ->
            val root = objectMap(parse(File(target, "files/asset-metadata/manifest.json").readBytes(), "manifest"), "manifest").toMutableMap(); root["entries"] = array(root["entries"], "entries").filter { objectMap(it, "entry")["alias"] != "usdcx" }; rewriteManifest(root, target)
        }
        expectVerifyFailure("duplicate identity") { target ->
            val root = objectMap(parse(File(target, "files/asset-metadata/manifest.json").readBytes(), "manifest"), "manifest").toMutableMap(); val entries = array(root["entries"], "entries").toMutableList(); entries.add(entries[0]); root["entries"] = entries; rewriteManifest(root, target)
        }
        expectVerifyFailure("duplicate ticker") { target ->
            val root = objectMap(parse(File(target, "files/asset-metadata/manifest.json").readBytes(), "manifest"), "manifest").toMutableMap(); val entries = array(root["entries"], "entries").map { LinkedHashMap(objectMap(it, "entry")) }; entries[1]["ticker"] = string(entries[0]["ticker"], "ticker").lowercase(Locale.ROOT); root["entries"] = entries; rewriteManifest(root, target)
        }
        expectVerifyFailure("changed PNG") { target -> File(target, string(objectMap(array(manifestRoot["entries"], "entries")[0], "entry")["logo"], "logo")).appendBytes(byteArrayOf(0)) }
        expectVerifyFailure("manifest SHA") { target -> File(target, "files/asset-metadata/manifest.sha256").writeText("0".repeat(64) + "\n") }
        expectVerifyFailure("traversal logo") { target ->
            val root = objectMap(parse(File(target, "files/asset-metadata/manifest.json").readBytes(), "manifest"), "manifest").toMutableMap(); val entries = array(root["entries"], "entries").map { LinkedHashMap(objectMap(it, "entry")) }; entries[0]["logo"] = "../escape.png"; root["entries"] = entries; rewriteManifest(root, target)
        }
    }
}
}

abstract class UpdateEmbeddedAssetMetadata : DefaultTask() {
    @get:InputFile abstract val catalogFile: RegularFileProperty
    @get:InputFile abstract val catalogDigestFile: RegularFileProperty
    @get:OutputDirectory abstract val resourcesDirectory: DirectoryProperty

    @TaskAction
    fun update() {
        val resources = resourcesDirectory.get().asFile
        val catalog = EmbeddedAssetMetadata.catalog(catalogFile.get().asFile, catalogDigestFile.get().asFile)
        val digest = EmbeddedAssetMetadata.readBounded(catalogDigestFile.get().asFile, 65).toString(StandardCharsets.US_ASCII).trim()
        val discovery = EmbeddedAssetMetadata.discoveryDigest(EmbeddedAssetMetadata.fetchDiscovery())
        require(discovery == digest) { "Controlled deployment catalog digest $discovery differs from reviewed $digest" }
        val response = EmbeddedAssetMetadata.fetchKoios(catalog)
        val generated = EmbeddedAssetMetadata.withCatalogDigest(EmbeddedAssetMetadata.generate(catalog, response), digest)
        EmbeddedAssetMetadata.publish(resources, generated)
        EmbeddedAssetMetadata.verify(resources, catalogFile.get().asFile, catalogDigestFile.get().asFile)
    }
}

abstract class VerifyEmbeddedAssetMetadata : DefaultTask() {
    @get:InputDirectory abstract val resourcesDirectory: DirectoryProperty
    @get:Internal abstract val catalogFile: RegularFileProperty
    @get:Internal abstract val catalogDigestFile: RegularFileProperty

    @TaskAction
    fun verify() = EmbeddedAssetMetadata.verify(resourcesDirectory.get().asFile, catalogFile.get().asFile, catalogDigestFile.get().asFile)
}

abstract class TestEmbeddedAssetMetadata : DefaultTask() {
    @get:InputDirectory abstract val resourcesDirectory: DirectoryProperty
    @get:Internal abstract val catalogFile: RegularFileProperty
    @get:Internal abstract val catalogDigestFile: RegularFileProperty

    @TaskAction
    fun test() = EmbeddedAssetMetadata.regression(resourcesDirectory.get().asFile, catalogFile.get().asFile, catalogDigestFile.get().asFile)
}

val embeddedResources = layout.projectDirectory.dir("shared/src/commonMain/composeResources")
val embeddedCatalog = embeddedResources.file("files/asset-metadata/catalog.json")
val embeddedCatalogDigest = embeddedResources.file("files/asset-metadata/catalog.sha256")

tasks.register<UpdateEmbeddedAssetMetadata>("updateEmbeddedAssetMetadata") {
    group = "asset metadata"
    description = "Refreshes reviewed token presentation metadata from Mainnet Koios."
    catalogFile.set(embeddedCatalog)
    catalogDigestFile.set(embeddedCatalogDigest)
    resourcesDirectory.set(embeddedResources)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.register<VerifyEmbeddedAssetMetadata>("verifyEmbeddedAssetMetadata") {
    group = "verification"
    description = "Verifies embedded token metadata without network access."
    resourcesDirectory.set(embeddedResources)
    catalogFile.set(embeddedCatalog)
    catalogDigestFile.set(embeddedCatalogDigest)
    outputs.upToDateWhen { false }
}

tasks.register<TestEmbeddedAssetMetadata>("testEmbeddedAssetMetadata") {
    group = "verification"
    description = "Runs focused offline embedded metadata regressions."
    resourcesDirectory.set(embeddedResources)
    catalogFile.set(embeddedCatalog)
    catalogDigestFile.set(embeddedCatalogDigest)
    outputs.upToDateWhen { false }
}


allprojects {
    dependencyLocking { lockAllConfigurations() }
}

tasks.register("androidCheck") {
    dependsOn(":shared:allTests", ":androidApp:testDebugUnitTest", ":androidApp:lintDebug", ":androidApp:assembleDebug", "verifyEmbeddedAssetMetadata", "testEmbeddedAssetMetadata")
}

tasks.register("androidReleaseCheck") {
    dependsOn(":shared:allTests", ":androidApp:test", ":androidApp:lintRelease", ":androidApp:verifyReleaseSecurity", ":androidApp:generateReleaseSbom", "verifyEmbeddedAssetMetadata", "testEmbeddedAssetMetadata")
}
