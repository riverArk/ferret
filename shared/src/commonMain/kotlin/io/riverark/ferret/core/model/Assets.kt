package io.riverark.ferret.core.model

import io.riverark.ferret.core.channel.ProtocolCrypto
import kotlinx.serialization.Serializable
import ferret.shared.generated.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi

@Serializable
enum class AssetPricing { ADA, USD_PEG }

@Serializable
data class ChannelAsset(
    val alias: String,
    val policyId: String?,
    val assetName: String?,
    val decimals: Int,
    val pricing: AssetPricing,
    val catalogDigest: String,
) {
    init {
        require(ALIAS.matches(alias))
        require(decimals in 0..19)
        require(SHA256.matches(catalogDigest))
        if (policyId == null || assetName == null) {
            require(policyId == null && assetName == null)
            require(alias == "ada" && decimals == 6 && pricing == AssetPricing.ADA)
        } else {
            require(alias != "ada")
            require(POLICY.matches(policyId) && ASSET_NAME.matches(assetName))
            require(pricing == AssetPricing.USD_PEG)
        }
    }

    val connectorUnit: String get() = policyId?.plus(assetName) ?: "lovelace"

    companion object {
        private val ALIAS = Regex("[a-z][a-z0-9_]{0,31}")
        private val POLICY = Regex("[0-9a-f]{56}")
        private val ASSET_NAME = Regex("(?:[0-9a-f]{2}){0,32}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

@Serializable
data class AssetAmount(val asset: ChannelAsset, val baseUnits: Long) {
    init { require(baseUnits >= 0) }

    operator fun plus(other: AssetAmount): AssetAmount {
        require(asset == other.asset) { "asset mismatch" }
        require(baseUnits <= Long.MAX_VALUE - other.baseUnits) { "asset amount overflow" }
        return copy(baseUnits = baseUnits + other.baseUnits)
    }

    operator fun minus(other: AssetAmount): AssetAmount {
        require(asset == other.asset) { "asset mismatch" }
        require(baseUnits >= other.baseUnits) { "negative asset amount" }
        return copy(baseUnits = baseUnits - other.baseUnits)
    }

    fun format(): String {
        if (asset.decimals == 0) return baseUnits.toString()
        val digits = baseUnits.toString().padStart(asset.decimals + 1, '0')
        val whole = digits.dropLast(asset.decimals)
        val fraction = digits.takeLast(asset.decimals).trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }
}

fun ChannelAsset.parseAmount(value: String): AssetAmount {
    require(value.isNotEmpty() && value.none { it == '+' || it == '-' || it == 'e' || it == 'E' })
    require(value.count { it == '.' } <= 1)
    val whole = value.substringBefore('.')
    val fraction = value.substringAfter('.', "")
    require(whole.isNotEmpty() && whole.all(Char::isDigit) && fraction.all(Char::isDigit))
    require(fraction.length <= decimals)
    val digits = (whole + fraction.padEnd(decimals, '0')).trimStart('0').ifEmpty { "0" }
    var units = 0L
    digits.forEach { digit ->
        val next = digit.digitToInt()
        require(units <= (Long.MAX_VALUE - next) / 10) { "asset amount overflow" }
        units = units * 10 + next
    }
    return AssetAmount(this, units)
}

data class AssetPresentation(
    val name: String,
    val ticker: String,
    val logoPath: String,
    val logoBytes: ByteArray,
) {
    override fun equals(other: Any?) = other is AssetPresentation && name == other.name && ticker == other.ticker &&
        logoPath == other.logoPath && logoBytes.contentEquals(other.logoBytes)
    override fun hashCode() = 31 * (31 * name.hashCode() + ticker.hashCode()) + logoBytes.contentHashCode()
}

class AssetCatalog internal constructor(
    assets: List<ChannelAsset>,
    val digest: String,
    presentation: Map<String, AssetPresentation>,
) {
    private val byAlias = assets.associateBy(ChannelAsset::alias)
    private val byUnit = assets.associateBy(ChannelAsset::connectorUnit)
    val assets: List<ChannelAsset> = assets.toList()
    val presentations: Map<String, AssetPresentation> = presentation.toMap()
    val ada: ChannelAsset get() = requireNotNull(byAlias["ada"])

    init {
        require(assets.size == byAlias.size && assets.size == byUnit.size)
        require(assets.all { it.catalogDigest == digest })
        require(byAlias.keys.containsAll(REQUIRED))
    }

    fun requireAsset(asset: ChannelAsset): ChannelAsset = requireNotNull(byAlias[asset.alias].takeIf { it == asset }) {
        "Asset catalog unavailable."
    }
    fun asset(alias: String): ChannelAsset? = byAlias[alias]
    fun assetForConnectorUnit(unit: String): ChannelAsset? = byUnit[unit]
    fun requireDiscoveryDigest(discoveryDigest: String?) {
        require(discoveryDigest == digest) { "Asset catalog unavailable." }
    }

    private companion object { val REQUIRED = setOf("ada", "usda", "usdcx", "usdm") }
}

@OptIn(ExperimentalResourceApi::class)
suspend fun loadEmbeddedAssetCatalog(
    crypto: ProtocolCrypto,
    readResource: suspend (String) -> ByteArray = Res::readBytes,
): AssetCatalog {
    val json = Json { ignoreUnknownKeys = false }
    val catalogBytes = readResource("files/asset-metadata/catalog.json")
    val catalogDigestBytes = readResource("files/asset-metadata/catalog.sha256")
    val manifestBytes = readResource("files/asset-metadata/manifest.json")
    val manifestDigestBytes = readResource("files/asset-metadata/manifest.sha256")
    require(catalogBytes.size <= 65_536 && manifestBytes.size <= 65_536)
    val digest = sidecar(catalogDigestBytes)
    require(crypto.sha256(catalogBytes).hex() == digest)
    require(crypto.sha256(manifestBytes).hex() == sidecar(manifestDigestBytes))

    val root = json.parseToJsonElement(catalogBytes.decodeToString()).jsonObject
    require(root.size <= 16 && root.keys.toList() == root.keys.sorted())
    require(root.toString().encodeToByteArray().contentEquals(catalogBytes)) { "Catalog is not canonical" }
    val identities = mutableSetOf<Pair<String, String>>()
    val assets = root.map { (key, raw) ->
        val definition = raw.exactObject(setOf("alias", "asset", "decimals", "pricing"))
        val alias = definition.string("alias")
        require(key == alias)
        val decimals = definition["decimals"]?.jsonPrimitive?.intOrNull ?: error("Invalid asset decimals")
        val identity = definition.getValue("asset").jsonObject
        val kind = identity.string("kind")
        val policyId: String?
        val assetName: String?
        when (kind) {
            "ada" -> {
                require(identity.keys == setOf("kind"))
                policyId = null
                assetName = null
            }
            "native" -> {
                require(identity.keys == setOf("kind", "policy_id", "asset_name"))
                policyId = identity.string("policy_id")
                assetName = identity.string("asset_name")
                require(identities.add(policyId to assetName))
            }
            else -> error("Unsupported asset kind $kind")
        }
        val pricingObject = definition.getValue("pricing").exactObject(setOf("kind"))
        val pricing = when (pricingObject.string("kind")) {
            "ada" -> AssetPricing.ADA
            "usd_peg" -> AssetPricing.USD_PEG
            else -> error("Unsupported asset pricing")
        }
        ChannelAsset(alias, policyId, assetName, decimals, pricing, digest)
    }

    val manifest = json.parseToJsonElement(manifestBytes.decodeToString()).exactObject(setOf("schema", "source", "catalog_digest", "entries"))
    require(manifest["schema"]?.jsonPrimitive?.intOrNull == 1 && manifest.string("catalog_digest") == digest)
    require(manifest.getValue("source").exactObject(setOf("url", "api_version", "spec_version")) == JsonObject(mapOf(
        "url" to JsonPrimitive("https://api.koios.rest/api/v1/asset_info"),
        "api_version" to JsonPrimitive("v1"),
        "spec_version" to JsonPrimitive("v1.4.2"),
    )))
    val known = assets.filter { it.policyId != null }.associateBy { it.policyId!! to it.assetName!! }
    val seen = mutableSetOf<Pair<String, String>>()
    val presentations = manifest.getValue("entries").jsonArray.associate { raw ->
        val row = raw.exactObject(setOf("alias", "policy_id", "asset_name", "fingerprint", "name", "ticker", "description", "url", "logo", "logo_sha256"))
        val identity = row.string("policy_id") to row.string("asset_name")
        val asset = requireNotNull(known[identity])
        require(seen.add(identity) && row.string("alias") == asset.alias)
        require(Regex("asset1[023456789acdefghjklmnpqrstuvwxyz]{38}").matches(row.string("fingerprint")))
        val path = row.string("logo")
        require(path == "drawable/asset_${identity.first}_${identity.second}.png")
        val logo = readResource(path)
        require(logo.size <= 262_144 && crypto.sha256(logo).hex() == row.string("logo_sha256"))
        asset.alias to AssetPresentation(row.string("name"), row.string("ticker"), path, logo)
    }
    require(seen == known.keys)
    return AssetCatalog(assets, digest, presentations)
}

private fun JsonElement.exactObject(fields: Set<String>): JsonObject = jsonObject.also { require(it.keys == fields) }
private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
private fun sidecar(bytes: ByteArray): String = bytes.decodeToString().also {
    require(it.length == 65 && it.endsWith('\n') && Regex("[0-9a-f]{64}").matches(it.dropLast(1)))
}.dropLast(1)
private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
