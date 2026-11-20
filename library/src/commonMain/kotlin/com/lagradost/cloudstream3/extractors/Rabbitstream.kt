package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.MD5
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("case\\s*0x[0-9a-f]+:(?![^;]*=partKey)\\s*\\w+\\s*=\\s*(\\w+)\\s*,\\s*\\w+\\s*=\\s*(\\w+);")


class Megacloud : Rabbitstream() {
    override val name = "Megacloud"
    override val mainUrl = "https://megacloud.tv"
    override val embed = "embed-2/ajax/e-1"
    private val scriptUrl = "$mainUrl/js/player/a/prod/e1-player.min.js"

    override suspend fun extractRealKey(sources: String): Pair<String, String> {
        val rawKeys = getKeys()
        val sourcesArray = sources.toCharArray()

        var extractedKey = ""
        var currentIndex = 0
        for (index in rawKeys) {
            // Audit-3 #47: these offsets come from the server's key
            // response; an out-of-range pair used to throw
            // ArrayIndexOutOfBounds and kill the extractor.
            val start = (index.getOrNull(0) ?: continue) + currentIndex
            val length = index.getOrNull(1) ?: continue
            val end = start + length
            for (i in start until end) {
                if (i !in sourcesArray.indices) break
                extractedKey += sourcesArray[i].toString()
                sourcesArray[i] = ' '
            }

            currentIndex += length
        }

        return extractedKey to sourcesArray.joinToString("").replace(" ", "")
    }

    private suspend fun getKeys(): List<List<Int>> {
        val script = app.get(scriptUrl).text
        fun matchingKey(value: String): String {
            return Regex(",$value=((?:0x)?([0-9a-fA-F]+))").find(script)?.groupValues?.get(1)
                ?.removePrefix("0x") ?: throw ErrorLoadingException("Failed to match the key")
        }

        val regex = hoistedRegex1
        val indexPairs = regex.findAll(script).toList().map { match ->
            val matchKey1 = matchingKey(match.groupValues[1])
            val matchKey2 = matchingKey(match.groupValues[2])
            try {
                listOf(matchKey1.toInt(16), matchKey2.toInt(16))
            } catch (_: NumberFormatException) {
                emptyList()
            }
        }.filter { it.isNotEmpty() }

        return indexPairs
    }
}

class Dokicloud : Rabbitstream() {
    override val name = "Dokicloud"
    override val mainUrl = "https://dokicloud.one"
}

// Code found in https://github.com/eatmynerds/key
// special credits to @eatmynerds for providing key
open class Rabbitstream : ExtractorApi() {
    override val name = "Rabbitstream"
    override val mainUrl = "https://rabbitstream.net"
    override val requiresReferer = false
    open val embed = "ajax/embed-4"
    open val key = "https://raw.githubusercontent.com/eatmynerds/key/e4/key.txt"

    private val aesCbc = CryptographyProvider.Default.get(AES.CBC)
    @OptIn(DelicateCryptographyApi::class)
    private val md5Hasher = CryptographyProvider.Default.get(MD5).hasher()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = url.substringAfterLast("/").substringBefore("?")
        val response = app.get(
            "$mainUrl/$embed/getSources?id=$id",
            referer = mainUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )

        val encryptedMap = response.parsedSafe<SourcesEncrypted>()
        val sources = encryptedMap?.sources
        val decryptedSources = if (sources == null || encryptedMap.encrypted == false) {
            response.parsedSafe<SourcesResponses>()
        } else {
            val (key, encData) = extractRealKey(sources)
            val decrypted = tryParseJson<List<Sources>>(decrypt(encData, key))
            SourcesResponses(
                sources = decrypted,
                tracks = encryptedMap.tracks,
            )
        }

        decryptedSources?.sources?.map { source ->
            M3u8Helper.generateM3u8(
                name,
                source?.file ?: return@map,
                "$mainUrl/",
            ).forEach(callback)
        }

        decryptedSources?.tracks?.map { track ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    track?.label ?: return@map,
                    track.file ?: return@map,
                )
            )
        }
    }

    open suspend fun extractRealKey(sources: String): Pair<String, String> {
        // Audit-8 #15: the key file is fetched from a third-party
        // repository — a moved/renamed repo answers "404: Not
        // Found", and the throwing parseJson let the raw
        // SerializationException escape instead of failing the
        // load cleanly.
        val rawKeys = tryParseJson<List<Int>>(app.get(key).text)
            ?: throw ErrorLoadingException("Invalid key file")
        val extractedKey = base64Encode(rawKeys.map { it.toByte() }.toByteArray())
        return extractedKey to sources
    }

    private suspend fun decrypt(input: String, key: String): String {
        // Audit-7 #42: a malformed sources payload (not base64,
        // or too short to hold the salt) threw a raw
        // IllegalArgumentException out of the decryption path.
        // Fail as a clean load error instead.
        val decoded = try {
            base64DecodeArray(input)
        } catch (e: IllegalArgumentException) {
            throw ErrorLoadingException("Malformed encrypted sources payload")
        }
        if (decoded.size < 16) {
            throw ErrorLoadingException("Encrypted sources payload too short")
        }
        return decryptSourceUrl(
            generateKey(
                salt = decoded.copyOfRange(8, 16),
                secret = key.encodeToByteArray(),
            ), input
        )
    }

    private suspend fun generateKey(salt: ByteArray, secret: ByteArray): ByteArray {
        var key = md5(secret + salt)
        var currentKey = key
        while (currentKey.size < 48) {
            key = md5(key + secret + salt)
            currentKey += key
        }

        return currentKey
    }

    private suspend fun md5(input: ByteArray): ByteArray =
        md5Hasher.hash(input)

    @OptIn(DelicateCryptographyApi::class)
    private suspend fun decryptSourceUrl(decryptionKey: ByteArray, sourceUrl: String): String {
        // Audit-7 #42: same guard as decrypt() — copyOfRange(16, size)
        // on a shorter payload is an IllegalArgumentException.
        val cipherData = try {
            base64DecodeArray(sourceUrl)
        } catch (e: IllegalArgumentException) {
            throw ErrorLoadingException("Malformed encrypted source URL")
        }
        if (cipherData.size <= 16) {
            throw ErrorLoadingException("Encrypted source URL too short")
        }
        val encrypted = cipherData.copyOfRange(16, cipherData.size)
        val keyBytes = decryptionKey.copyOfRange(0, 32)
        val ivBytes = decryptionKey.copyOfRange(32, decryptionKey.size)

        val aesKey = aesCbc.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
        // Audit-9 #9: a valid-base64 garbage payload passes the
        // decode + length guards above but throws in the decrypt
        // itself (block alignment / padding) — surface it as the
        // ErrorLoadingException this function uses everywhere else.
        val decryptedData = try {
            aesKey.cipher(padding = true).decryptWithIv(ivBytes, encrypted)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to decrypt source URL")
        }
        return decryptedData.decodeToString()
    }

    @Serializable
    data class Tracks(
        @JsonProperty("file") @SerialName("file") val file: String? = null,
        @JsonProperty("label") @SerialName("label") val label: String? = null,
        @JsonProperty("kind") @SerialName("kind") val kind: String? = null,
    )

    @Serializable
    data class Sources(
        @JsonProperty("file") @SerialName("file") val file: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
        @JsonProperty("label") @SerialName("label") val label: String? = null,
    )

    @Serializable
    data class SourcesResponses(
        @JsonProperty("sources") @SerialName("sources") val sources: List<Sources?>? = emptyList(),
        @JsonProperty("tracks") @SerialName("tracks") val tracks: List<Tracks?>? = emptyList(),
    )

    @Serializable
    data class SourcesEncrypted(
        @JsonProperty("sources") @SerialName("sources") val sources: String? = null,
        @JsonProperty("encrypted") @SerialName("encrypted") val encrypted: Boolean? = null,
        @JsonProperty("tracks") @SerialName("tracks") val tracks: List<Tracks?>? = emptyList(),
    )
}
