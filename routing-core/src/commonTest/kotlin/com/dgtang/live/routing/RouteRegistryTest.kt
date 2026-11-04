package com.dgtang.live.routing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteRegistryTest {
    private interface Target
    @Serializable private data class PublicPair(val first: String, val second: String) : Target
    @Serializable private data class Query(val text: String, val privateValue: String = "") : Target
    @Serializable private data class Article(val id: Long = 0, val title: String = "") : Target
    @Serializable private data class TextId(val id: String = "0") : Target
    @Serializable private data class RendererPage(val enabled: Boolean = false) : Target
    @Serializable private data class Payload(val value: String)
    @Serializable private enum class Mode { STANDARD, SPECIAL }
    @Serializable private data class InternalPage(
        val payload: Payload,
        val mode: Mode = Mode.STANDARD,
        val optional: String? = null,
    ) : Target

    private val query = definition("query", "/query", Query.serializer(), setOf("text"))
    private val article = definition("article", "/article/{id}", Article.serializer(), setOf("id"), setOf("id"))
    private val textId = definition("text_id", "/text-id/{id}", TextId.serializer(), setOf("id"), setOf("id"))
    private val renderer = definition("renderer", "/renderer", RendererPage.serializer(), setOf("enabled"), exposure = RouteExposure.RENDERER)
    private val internal = definition("internal", "/internal", InternalPage.serializer(), exposure = RouteExposure.INTERNAL)
    private val registry = RouteRegistry(listOf(query, article, textId, renderer, internal)) { target ->
        if (target is Query) target.text.trim().takeIf { it.isNotEmpty() }?.let { target.copy(text = it) } else target
    }
    private val host = "links.example.com"

    @Test
    fun internalUriRoundTripsNestedDataWithoutExposingItToPublicParser() {
        val page = InternalPage(Payload("x".repeat(20_000)), Mode.SPECIAL)
        val uri = assertNotNull(registry.internalUri(page))
        assertEquals(page, registry.parseInternal(uri))
        assertEquals(page, registry.decodeInternalTarget("internal", json.encodeToJsonElement(InternalPage.serializer(), page).jsonObject))
        assertEquals(Article(12, "正文"), registry.parseInternal("/article/12?title=正文"))
        assertNull(registry.parsePublic(host, "https://$host$uri"))
        assertNull(registry.parseInternal("//$host/internal"))
        assertNull(registry.internalUri(InternalPage(Payload("x".repeat(65_537)))))
        // 内部 _args 不能给公开页面注入私有字段。
        val queryUri = assertNotNull(registry.internalUri(Query("text", "secret")))
        assertEquals(Query("text", "secret"), registry.parseInternal(queryUri))
        assertNull(registry.parsePublic(host, "https://$host$queryUri"))
    }

    @Test
    fun internalPathAndQueryUseInternalStringBudgetWithoutRelaxingPublicInput() {
        val internalPaths = RouteRegistry(listOf(definition("internal_path", "/internal-path/{text}",
            Query.serializer(), exposure = RouteExposure.INTERNAL)))
        val publicPaths = RouteRegistry(listOf(definition("public_path", "/internal-path/{text}",
            Query.serializer(), setOf("text"))))
        for (text in listOf("x".repeat(4097), "中".repeat(1366), "x".repeat(64 * 1024))) {
            val page = Query(text)
            val uri = assertNotNull(internalPaths.internalUri(page))
            assertTrue(page == internalPaths.parseInternal(uri), "内部路径往返，字节数=${text.encodeToByteArray().size}")
            assertTrue(Query("short", text) == internalPaths.parseInternal("/internal-path/short?privateValue=$text"),
                "内部查询使用同等字符串预算")
            assertNull(publicPaths.parsePublic(host, "https://$host$uri"))
            assertNull(publicPaths.publicUri(host, page))
        }
        assertNull(internalPaths.internalUri(Query("x".repeat(64 * 1024 + 1))))
        assertNull(internalPaths.parseInternal("/internal-path/" + "x".repeat(64 * 1024 + 1)))
        assertNull(internalPaths.parseInternal("/internal-path/short?privateValue=" + "x".repeat(64 * 1024 + 1)))
        val publicLimit = Query("x".repeat(4096))
        assertEquals(publicLimit, publicPaths.parsePublic(host, assertNotNull(publicPaths.publicUri(host, publicLimit))))
    }

    @Test
    fun internalTotalBudgetExcludesSyntheticParsingHost() {
        val overhead = assertNotNull(registry.internalUri(InternalPage(Payload("")))).encodeToByteArray().size
        val escapedByteSize = assertNotNull(registry.internalUri(InternalPage(Payload("\u0000")))).encodeToByteArray().size - overhead
        val payloadBudget = MAX_INTERNAL_ROUTE_URI_BYTES - overhead
        val value = "\u0000".repeat(payloadBudget / escapedByteSize) + "a".repeat(payloadBudget % escapedByteSize)
        assertTrue(value.encodeToByteArray().size <= 64 * 1024)
        val target = InternalPage(Payload(value))
        val uri = assertNotNull(registry.internalUri(target))
        assertEquals(MAX_INTERNAL_ROUTE_URI_BYTES, uri.encodeToByteArray().size)
        assertTrue(target == registry.parseInternal(uri), "内部总预算按相对 URI 计量")
        assertNull(registry.internalUri(InternalPage(Payload(value + "a"))))
        assertNull(registry.parseInternal(uri + "&ignored=1"))
    }

    @Test
    fun generatedPublicUriChecksEncodedTotalBudgetSeparatelyFromFieldsAndEnvelope() {
        val pairs = RouteRegistry(listOf(definition("pair", "/pair", PublicPair.serializer(), setOf("first", "second"))))
        val overhead = assertNotNull(pairs.publicUri(host, PublicPair("", ""))).encodeToByteArray().size
        val first = "中".repeat(1365)
        val second = "a".repeat(MAX_ROUTE_URI_BYTES - overhead - 1365 * 9)
        val exact = PublicPair(first, second)
        val uri = assertNotNull(pairs.publicUri(host, exact))
        assertEquals(MAX_ROUTE_URI_BYTES, uri.encodeToByteArray().size)
        assertEquals(exact, pairs.parsePublic(host, uri))
        for (overflow in listOf(PublicPair(first, second + "a"), PublicPair("中".repeat(1000), "中".repeat(1000)))) {
            assertNotNull(pairs.encodePublicTarget(overflow), "各字段与公开信封仍在各自预算内")
            assertNull(pairs.publicUri(host, overflow), "百分号编码后超过 URI 总预算")
        }
    }

    @Test
    fun exactHostAndBrandSchemeUseTheSamePublicRegistry() {
        assertEquals(Article(12), registry.parsePublic(host, "https://$host/article/12"))
        assertEquals(Article(12), registry.parsePublic(host, "brand://page/article/12", "brand"))
        for (url in listOf("http://$host/article/12", "https://other.$host/article/12",
            "https://$host.evil/article/12", "other://page/article/12", "brand://other/article/12")) {
            assertNull(registry.parsePublic(host, url, "brand"), url)
        }
        assertNull(registry.parsePublic(host, "brand://page/article/12"))
        assertNull(registry.parsePublic("", "brand://page/article/12", "brand"))
        assertNull(registry.parsePublic(host, "https://$host/missing"))
    }

    @Test
    fun projectionsStripPrivateDataAndUseNormalizedTargets() {
        assertEquals("https://$host/query?text=hello", registry.publicProjection(host,
            "https://user:password@$host/query?text=%20hello%20&token=secret&privateValue=hidden#private"))
        assertEquals("https://$host/article/12", registry.publicProjection(host, "brand://page/article/%31%32", "brand"))
        val public = assertNotNull(registry.encodePublicTarget(Query(" hello ", "private")))
        assertEquals(buildJsonObject {
            put("name", "query"); put("arguments", buildJsonObject { put("text", "hello") })
        }, public)
        assertNull(registry.decodePublicTarget("query", buildJsonObject { put("text", "hello"); put("privateValue", "forged") }))
        assertNull(registry.parsePublic(host, "https://$host/query?text=%20"))
        assertEquals("https://$host/query?text=hello", registry.publicUri(host, Query(" hello ", "private")))
        assertNull(registry.publicUri(host, RendererPage()))
        assertNull(registry.publicUri(host, InternalPage(Payload("secret"))))
        assertNotNull(registry.definition("query"))
        assertEquals(query, registry.definitionFor(Query("q")))
    }

    @Test
    fun malformedUtf8AndDuplicateKnownParametersAreRejected() {
        for (path in listOf("/query?text=a&text=b", "/query?text=a&t%65xt=b", "/article/1?id=2",
            "/query?text=a&privateValue=x&privateValue=y", "/query?text=%", "/query?text=%ZZ",
            "/query?text=%+1", "/query?text=%C3%28", "/query?text=%ED%A0%80")) {
            assertNull(registry.parsePublic(host, "https://$host$path"), path)
        }
        assertEquals(Query("a"), registry.parsePublic(host, "https://$host/query?text=a&unknown=x&unknown=y"))
    }

    @Test
    fun exposureDistinguishesUriRendererAndInternalVersionPayloads() {
        assertNull(registry.parsePublic(host, "https://$host/renderer?enabled=true"))
        assertEquals(RendererPage(true), registry.decodePublicTarget("renderer", buildJsonObject { put("enabled", true) }))
        assertNotNull(registry.encodePublicTarget(RendererPage()))
        assertNull(registry.decodePublicTarget("internal", buildJsonObject { put("payload", buildJsonObject { put("value", "v") }) }))
        assertNull(registry.encodePublicTarget(InternalPage(Payload("v"))))
        assertEquals(InternalPage(Payload("v"), Mode.SPECIAL), registry.decodeInternalTarget("internal", buildJsonObject {
            put("payload", buildJsonObject { put("value", "v") }); put("mode", "SPECIAL"); put("optional", JsonNull)
        }))
    }

    @Test
    fun descriptorsKeepJsonTypesEnumsNullableDefaultsAndNestedPayloadsStrict() {
        for (args in listOf(buildJsonObject { put("id", "12") }, buildJsonObject { put("id", true) })) {
            assertNull(registry.decodePublicTarget("article", args))
        }
        assertNull(registry.decodeInternalTarget("renderer", buildJsonObject { put("enabled", 1) }))
        assertNull(registry.decodeInternalTarget("renderer", buildJsonObject { put("unknown", false) }))
        for (mode in listOf("unknown", "null")) {
            assertNull(registry.decodeInternalTarget("internal", buildJsonObject {
                put("payload", buildJsonObject { put("value", "v") }); put("mode", mode)
            }))
        }
        assertNull(registry.decodeInternalTarget("internal", buildJsonObject {
            put("payload", buildJsonObject { put("value", 1) })
        }))
        assertNull(registry.decodeInternalTarget("internal", JsonObject(emptyMap())))
    }

    @Test
    fun positiveDefaultsAreCheckedAfterDecodingButInternalZeroRemainsValid() {
        assertEquals(Article(0), registry.decodeInternalTarget("article", JsonObject(emptyMap())))
        assertNull(registry.decodePublicTarget("article", JsonObject(emptyMap())))
        for (id in listOf("0", "-1", "9223372036854775808")) {
            assertNull(registry.parsePublic(host, "https://$host/article/$id"))
        }
        assertEquals(Article(Long.MAX_VALUE), registry.parsePublic(host, "https://$host/article/${Long.MAX_VALUE}"))
        assertEquals(TextId(), registry.decodeInternalTarget("text_id", JsonObject(emptyMap())))
        assertNull(registry.decodePublicTarget("text_id", JsonObject(emptyMap())))
        assertNull(registry.decodePublicTarget("text_id", buildJsonObject { put("id", 7) }))
        assertEquals(TextId("7"), registry.parsePublic(host, "https://$host/text-id/0007"))
        assertEquals("https://$host/text-id/7", registry.publicProjection(host, "https://$host/text-id/0007"))
    }

    @Test
    fun uriUtf8AndEnvelopeBudgetsArePreserved() {
        assertNotNull(registry.decodePublicTarget("query", buildJsonObject { put("text", "a".repeat(4096)) }))
        assertNull(registry.decodePublicTarget("query", buildJsonObject { put("text", "a".repeat(4097)) }))
        assertNull(registry.decodePublicTarget("query", buildJsonObject { put("text", "中".repeat(1366)) }))
        assertNull(registry.parsePublic(host, "https://$host/query?text=a&ignored=" + "a".repeat(16 * 1024)))
        assertEquals(Query("a".repeat(16 * 1024)), registry.decodeInternalTarget("query", buildJsonObject { put("text", "a".repeat(16 * 1024)) }))
        assertNull(registry.decodeInternalTarget("query", buildJsonObject { put("text", "a".repeat(65_537)) }))
        assertNull(registry.parsePublic(host, "https://$host/query?text=" + "%00".repeat(4096)))
        assertNull(registry.encodePublicTarget(Query("\u0000".repeat(4096))))
    }

    private inline fun <reified T : Target> definition(
        name: String,
        path: String,
        serializer: KSerializer<T>,
        publicParameters: Set<String> = emptySet(),
        positiveParameters: Set<String> = emptySet(),
        exposure: RouteExposure = RouteExposure.PUBLIC,
    ): RouteDefinition<Target> = RouteDefinition(name, path, exposure, publicParameters, positiveParameters,
        serializer.descriptor,
        decode = { json.decodeFromJsonElement(serializer, it) },
        encode = { target -> (target as? T)?.let { json.encodeToJsonElement(serializer, it).jsonObject } },
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; coerceInputValues = true }
}
