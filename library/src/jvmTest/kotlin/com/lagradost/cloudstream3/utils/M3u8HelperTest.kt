package com.lagradost.cloudstream3.utils

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class M3u8HelperTest {
    private lateinit var server: HttpServer
    private lateinit var host: String
    private val requestedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val segment = ByteArray(256) { (it or 0x80).toByte() }

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        host = "127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requestedPaths += path
            val body = when (path) {
                "/a/b/index.m3u8" -> playlist(key = null).encodeToByteArray()
                "/a/b/encrypted.m3u8" -> playlist(key = "/keys/k.bin").encodeToByteArray()
                "/a/b/aes.m3u8" -> aesPlaylist(keyLine = "#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k.bin\"").encodeToByteArray()
                "/a/b/aes-reordered.m3u8" -> aesPlaylist(keyLine = "#EXT-X-KEY:URI=\"/keys/k.bin\",METHOD=AES-128").encodeToByteArray()
                "/a/b/rotation.m3u8" -> rotationPlaylist().encodeToByteArray()
                "/a/b/sample-aes.m3u8" -> aesPlaylist(keyLine = "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"/keys/k.bin\"").encodeToByteArray()
                "/aes/seg0.ts" -> aesCipherSegment
                "/aes/seg1.ts" -> segment
                "/a/b/seg0.ts", "/root/seg1.ts", "/proto/seg2.ts" -> segment
                "/keys/k.bin" -> ByteArray(16)
                else -> null
            }
            val bytes = body ?: "404 not found".encodeToByteArray()
            exchange.sendResponseHeaders(if (body == null) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun playlist(key: String?): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:10")
        if (key != null) appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"$key\"")
        appendLine("#EXTINF:10.0,")
        appendLine("seg0.ts")
        appendLine("#EXTINF:10.0,")
        appendLine("/root/seg1.ts")
        appendLine("#EXTINF:10.0,")
        appendLine("//$host/proto/seg2.ts")
        appendLine("#EXT-X-ENDLIST")
    }

    @Test
    fun hslLazyResolvesSegmentUrlsAgainstPlaylistUrl() = runBlocking {
        val data = M3u8Helper2.hslLazy(
            M3u8Helper.M3u8Stream("http://$host/a/b/index.m3u8?token=abc"),
            requireAudio = false,
        )

        assertEquals(
            listOf(
                "http://$host/a/b/seg0.ts",
                "http://$host/root/seg1.ts",
                "http://$host/proto/seg2.ts",
            ),
            data.allTsLinks.map { it.url },
        )
        for (index in 0 until data.size) {
            assertContentEquals(segment, data.resolveLink(index))
        }
    }

    // Audit-5 #27: fixtures for the Audit-4 #11 key-range branch
    // and the Audit-5 #18 attribute-order parsing. aesCipherSegment
    // is aesPlainSegment encrypted with AES-128-CBC, key = 16 zero
    // bytes (what /keys/k.bin serves), IV = 16 zero bytes — the
    // default IV of segment index 0 at media sequence 0.
    private val aesPlainSegment = ByteArray(240) { ((it * 7 + 3) % 256).toByte() }

    private val aesCipherSegment = byteArrayOf(
        9, 4, 126, -18, -24, -88, 84, 88, 38, -15, 75, -26, 52, 7, -72, -2,
        42, -43, 80, -109, -95, 114, -16, 97, 80, -3, 122, -35, -63, 88, 11, 35,
        -62, 6, -60, 65, -86, 91, 123, -98, -73, 81, 71, 57, -2, -1, -81, -23,
        24, -120, 91, 115, -127, -109, -122, -112, -68, 37, -42, 104, 77, -18, 58, 96,
        122, 23, 40, -82, -94, -97, -65, -24, 38, 115, -115, 23, -16, 126, 32, 97,
        53, 84, -4, 71, -49, -33, 12, -117, 112, -18, -44, -66, -74, 25, -25, -2,
        21, 46, 87, -86, 75, -103, -94, -122, 106, -73, 31, 70, -70, 2, -22, -94,
        61, -49, 55, 48, 28, 114, 41, -120, -69, -27, 19, -127, -73, -58, -50, 68,
        -40, -66, -56, -108, 69, -48, 65, 31, 56, 73, -117, -43, -74, 30, 45, 104,
        104, 86, -126, -52, -89, 125, 70, 3, 121, 75, 73, 48, 29, -61, -16, 55,
        74, -22, 13, 85, 62, -45, 18, -48, -67, -66, -34, -62, 2, -56, 9, 13,
        81, -37, -101, -65, 2, 91, -46, 78, 7, 97, 113, 121, 70, -34, 69, -30,
        109, 2, 85, 114, -101, -4, 25, 47, 108, 10, -99, -32, -34, -1, -95, 7,
        -42, 101, 109, 69, -69, 79, -6, 42, -26, -101, 87, -46, 30, -116, 2, -125,
        -17, -37, -51, -70, -102, 44, 12, 89, -53, 126, -110, -8, -68, 39, -12, 121,
        19, -59, -36, 13, 31, 126, -107, 108, 0, 123, 21, 125, 118, -5, 68, -57,
    )

    private fun aesPlaylist(keyLine: String): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:10")
        appendLine(keyLine)
        appendLine("#EXTINF:10.0,")
        appendLine("/aes/seg0.ts")
        appendLine("#EXT-X-ENDLIST")
    }

    private fun rotationPlaylist(): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:10")
        appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k.bin\"")
        appendLine("#EXTINF:10.0,")
        appendLine("/aes/seg0.ts")
        appendLine("#EXT-X-KEY:METHOD=NONE")
        appendLine("#EXTINF:10.0,")
        appendLine("/aes/seg1.ts")
        appendLine("#EXT-X-ENDLIST")
    }

    @Test
    fun hslLazyResolvesKeyUrlAgainstPlaylistUrl() = runBlocking {
        M3u8Helper2.hslLazy(
            M3u8Helper.M3u8Stream("http://$host/a/b/encrypted.m3u8"),
            requireAudio = false,
        )

        assertTrue("/keys/k.bin" in requestedPaths, "requested: $requestedPaths")
    }

    @Test
    fun hslLazyDecryptsAes128Segments() = runBlocking {
        val data = M3u8Helper2.hslLazy(
            M3u8Helper.M3u8Stream("http://$host/a/b/aes.m3u8"),
            requireAudio = false,
        )
        assertContentEquals(aesPlainSegment, data.resolveLink(0))
    }

    @Test
    fun hslLazyKeyAttributeOrderDoesNotMatter() = runBlocking {
        // Audit-5 #18: URI before METHOD is legal RFC 8216
        // attribute order; the old single-order regex skipped
        // such tags and the segment stayed encrypted garbage.
        val data = M3u8Helper2.hslLazy(
            M3u8Helper.M3u8Stream("http://$host/a/b/aes-reordered.m3u8"),
            requireAudio = false,
        )
        assertContentEquals(aesPlainSegment, data.resolveLink(0))
    }

    @Test
    fun hslLazyMethodNoneRangeStaysPlaintext() = runBlocking {
        // Audit-4 #11: after a rotation to METHOD=NONE, segments
        // are plain — they must not be run through the previous
        // key, and the encrypted segment before them still
        // decrypts.
        val data = M3u8Helper2.hslLazy(
            M3u8Helper.M3u8Stream("http://$host/a/b/rotation.m3u8"),
            requireAudio = false,
        )
        assertContentEquals(aesPlainSegment, data.resolveLink(0))
        assertContentEquals(segment, data.resolveLink(1))
    }

    @Test
    fun hslLazyRejectsUnsupportedEncryptionMethod() = runBlocking {
        assertFailsWith<IllegalStateException> {
            M3u8Helper2.hslLazy(
                M3u8Helper.M3u8Stream("http://$host/a/b/sample-aes.m3u8"),
                requireAudio = false,
            )
        }
    }
}
