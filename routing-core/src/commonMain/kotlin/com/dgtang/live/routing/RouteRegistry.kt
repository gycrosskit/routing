package com.dgtang.live.routing

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodedPath
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** URI 经原生窗口与 Module 转发时共用预算，不与单参数或公开信封容量混用。 */
const val MAX_ROUTE_URI_BYTES = 16 * 1024
// 内部动态页面原本允许 64 KiB JSON；百分号编码后的预算单独计算，不放宽公开入口。
const val MAX_INTERNAL_ROUTE_URI_BYTES = 512 * 1024
private const val MAX_INTERNAL_STRING_BYTES = 64 * 1024
// Module 还会添加 snapshot 和 wait 包装，公开目标预留 4 KiB。
private const val MAX_PUBLIC_TARGET_BYTES = 12 * 1024
private const val MAX_STRING_BYTES = 4096

/** 只解释可信路由定义；业务规范化由消费者提供，不持有品牌或页面状态。 */
@OptIn(ExperimentalSerializationApi::class)
class RouteRegistry<T>(
    private val definitions: List<RouteDefinition<T>>,
    private val normalize: (T) -> T? = { it },
) {

    fun definition(name: String): RouteDefinition<T>? = definitions.firstOrNull { it.name == name }

    fun parsePublic(host: String, rawUrl: String, scheme: String? = null): T? = safely {
        val trustedHost = host.normalizedHost().takeIf(String::isNotEmpty) ?: return@safely null
        val url = parseUrl(rawUrl) ?: return@safely null
        val protocol = url.protocol.name.lowercase()
        val allowed = protocol == "https" && url.host.normalizedHost() == trustedHost ||
            (!scheme.isNullOrBlank() && protocol == scheme.lowercase() && url.host == "page")
        if (!allowed) return@safely null
        parse(url)
    }

    fun publicProjection(host: String, rawUrl: String, scheme: String? = null): String? =
        parsePublic(host, rawUrl, scheme)?.let { publicUri(host, it) }

    /** 只编码声明的公开参数；调用者可以复用已解析目标，不重复解析原始 URI。 */
    fun publicUri(host: String, target: T): String? = safely {
        val definition = definitionFor(target)?.takeIf { it.exposure == RouteExposure.PUBLIC } ?: return@safely null
        val arguments = publicArguments(definition, target) ?: return@safely null
        val pathParameters = definition.path.pathParts().mapNotNull(::parameterName).toSet()
        val path = definition.path.pathParts().joinToString("/", prefix = "/") { part ->
            (parameterName(part)?.let { (arguments[it] as? JsonPrimitive)?.content } ?: part).encodeURLPathPart()
        }
        URLBuilder("https://${host.normalizedHost()}").apply {
            encodedPath = path
            arguments.forEach { (name, value) ->
                if (name !in pathParameters) parameters.append(name, (value as JsonPrimitive).content)
            }
        }.buildString().takeIf { it.encodeToByteArray().size <= MAX_ROUTE_URI_BYTES }
    }

    fun encodePublicTarget(target: T): JsonObject? = safely {
        val definition = definitionFor(target)?.takeUnless { it.exposure == RouteExposure.INTERNAL } ?: return@safely null
        val arguments = publicArguments(definition, target) ?: return@safely null
        JsonObject(mapOf("name" to JsonPrimitive(definition.name), "arguments" to arguments))
    }

    /** 可信信封只保留声明允许的公开字段；调用者无需了解信封的 JSON 布局。 */
    fun projectPublicTarget(target: T): T? = safely {
        val encoded = encodePublicTarget(target) ?: return@safely null
        decodePublicTarget((encoded.getValue("name") as JsonPrimitive).content, encoded.getValue("arguments") as JsonObject)
    }

    fun decodeInternalTarget(name: String, args: JsonObject): T? = safely {
        if (args.toString().encodeToByteArray().size > MAX_INTERNAL_ROUTE_URI_BYTES) return@safely null
        decode(definition(name) ?: return@safely null, args, public = false, stringBudget = MAX_INTERNAL_STRING_BYTES)
    }

    fun decodePublicTarget(name: String, args: JsonObject): T? = safely {
        if (args.toString().encodeToByteArray().size > MAX_ROUTE_URI_BYTES) return@safely null
        val definition = definitions.firstOrNull { it.name == name && it.exposure != RouteExposure.INTERNAL }
            ?: return@safely null
        decode(definition, args, public = true)
    }

    /** 内部路径只能由可信调用入口使用，绝不回退为公开链接解析。 */
    fun parseInternal(rawUri: String, ignoredParameters: Set<String> = emptySet()): T? = safely {
        if (!rawUri.startsWith("/") || rawUri.startsWith("//")) return@safely null
        val origin = "https://route.invalid"
        val url = parseUrl("$origin$rawUri", MAX_INTERNAL_ROUTE_URI_BYTES + origin.length) ?: return@safely null
        parse(url, public = false, ignoredParameters = ignoredParameters)
    }

    /** 嵌套业务参数保存在独立信封，与消费者的扩展 Query 分开。不得把此 URI 输出到日志或外链。 */
    fun internalUri(target: T): String? = safely {
        val definition = definitionFor(target) ?: return@safely null
        val arguments = definition.encode(target) ?: return@safely null
        if (!validValue(arguments, definition.descriptor, MAX_INTERNAL_STRING_BYTES)) return@safely null
        val path = definition.path.pathParts().joinToString("/", prefix = "/") { part ->
            (parameterName(part)?.let { (arguments[it] as? JsonPrimitive)?.content } ?: part).encodeURLPathPart()
        }
        URLBuilder("https://route.invalid").apply {
            encodedPath = path
            if (arguments.isNotEmpty()) parameters.append("_args", arguments.toString())
        }.build().let { url ->
            (url.encodedPath + if (url.encodedQuery.isEmpty()) "" else "?${url.encodedQuery}")
                .takeIf { it.encodeToByteArray().size <= MAX_INTERNAL_ROUTE_URI_BYTES }
        }
    }

    private fun parse(url: Url, public: Boolean = true, ignoredParameters: Set<String> = emptySet()): T? {
        val stringBudget = if (public) MAX_STRING_BYTES else MAX_INTERNAL_STRING_BYTES
        val actual = url.segments.filter(String::isNotEmpty)
        val definition = definitions.firstOrNull { definition ->
            (!public || definition.exposure == RouteExposure.PUBLIC) &&
                definition.path.pathParts().let { expected ->
                    expected.size == actual.size && expected.indices.all { index ->
                        parameterName(expected[index]) != null || expected[index] == actual[index]
                    }
                }
        } ?: return null
        if (!public && url.fragment.isNotEmpty()) return null
        val arguments = linkedMapOf<String, JsonElement>()
        if (!public) {
            val payloads = url.parameters.getAll("_args")
            if (payloads != null) {
                if (payloads.size != 1) return null
                val payload = Json.parseToJsonElement(payloads.single()) as? JsonObject ?: return null
                arguments.putAll(payload)
            }
        }
        definition.path.pathParts().forEachIndexed { index, part ->
            parameterName(part)?.let { name ->
                if (public && name !in definition.publicParameters) return null
                val value = uriValue(definition, name, actual[index], stringBudget) ?: return null
                if (name in arguments && arguments[name] != value) return null
                arguments[name] = value
            }
        }
        for (name in url.parameters.names()) {
            if (!public && name in ignoredParameters) continue
            val known = definition.descriptor.getElementIndex(name) >= 0
            if (!known) continue
            val values = url.parameters.getAll(name) ?: return null
            if (values.size != 1 || name in arguments) return null
            if (public && name !in definition.publicParameters) continue
            arguments[name] = uriValue(definition, name, values.single(), stringBudget) ?: return null
        }
        return decode(definition, JsonObject(arguments), public = public, normalized = public, stringBudget = stringBudget)
    }

    private fun uriValue(definition: RouteDefinition<T>, name: String, value: String, stringBudget: Int): JsonElement? {
        val index = definition.descriptor.getElementIndex(name)
        if (index < 0 || value.encodeToByteArray().size > stringBudget) return null
        val descriptor = definition.descriptor.getElementDescriptor(index)
        if (value == "null" && descriptor.isNullable) return JsonNull
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> JsonPrimitive(value)
            PrimitiveKind.INT -> value.toIntOrNull()?.let(::JsonPrimitive)
            PrimitiveKind.LONG -> value.toLongOrNull()?.let(::JsonPrimitive)
            PrimitiveKind.BOOLEAN -> when (value) { "true" -> JsonPrimitive(true); "false" -> JsonPrimitive(false); else -> null }
            SerialKind.ENUM -> value.takeIf { descriptor.getElementIndex(it) >= 0 }?.let(::JsonPrimitive)
            else -> null
        }
    }

    private fun decode(definition: RouteDefinition<T>, arguments: JsonObject, public: Boolean, normalized: Boolean = true, stringBudget: Int = MAX_STRING_BYTES): T? {
        if (public && arguments.keys.any { it !in definition.publicParameters }) return null
        if (!validValue(arguments, definition.descriptor, stringBudget)) return null
        if (public && arguments.keys.any { name ->
            definition.descriptor.getElementDescriptor(definition.descriptor.getElementIndex(name)).kind !in
                setOf(PrimitiveKind.STRING, PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.BOOLEAN, SerialKind.ENUM)
        }) return null
        val parameters = if (public) JsonObject(arguments.mapValues { (name, value) ->
            if (name in definition.positiveParameters && value is JsonPrimitive && value.isString) {
                value.content.toIntOrNull()?.let { JsonPrimitive(it.toString()) } ?: value
            } else value
        }) else arguments
        val target = definition.decode(parameters)
        val result = if (normalized) normalize(target) ?: return null else target
        // 公开参数连默认值一起复核；内部预约允许原有的无房间默认值 0。
        val encoded = definition.encode(result) ?: return null
        if (!validValue(encoded, definition.descriptor, stringBudget) || public && !validPositiveParameters(definition, encoded)) return null
        if (public) {
            val publicArguments = JsonObject(encoded.filterKeys { it in definition.publicParameters })
            val envelope = JsonObject(mapOf("name" to JsonPrimitive(definition.name), "arguments" to publicArguments))
            if (envelope.toString().encodeToByteArray().size > MAX_PUBLIC_TARGET_BYTES) return null
        }
        return result
    }

    private fun publicArguments(definition: RouteDefinition<T>, target: T): JsonObject? {
        val encoded = definition.encode(target) ?: return null
        val arguments = JsonObject(encoded.filterKeys { it in definition.publicParameters })
        val decoded = decode(definition, arguments, public = true) ?: return null
        return definition.encode(decoded)?.let { result -> JsonObject(result.filterKeys { it in definition.publicParameters }) }
    }

    fun definitionFor(target: T): RouteDefinition<T>? =
        definitions.firstOrNull { it.encode(target) != null }

    private fun validPositiveParameters(definition: RouteDefinition<T>, arguments: JsonObject): Boolean =
        definition.positiveParameters.all { name ->
            val value = (arguments[name] as? JsonPrimitive)?.content
            val index = definition.descriptor.getElementIndex(name)
            if (index < 0) false
            else if (definition.descriptor.getElementDescriptor(index).kind == PrimitiveKind.LONG) {
                value?.toLongOrNull()?.let { it > 0 } == true
            } else value?.toIntOrNull()?.let { it > 0 } == true
        }

    private fun validValue(value: JsonElement, descriptor: SerialDescriptor, stringBudget: Int = MAX_STRING_BYTES): Boolean {
        if (value == JsonNull) return descriptor.isNullable
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> value is JsonPrimitive && value.isString && value.content.encodeToByteArray().size <= stringBudget
            PrimitiveKind.INT -> value is JsonPrimitive && !value.isString && value.intOrNull != null
            PrimitiveKind.LONG -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            PrimitiveKind.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            SerialKind.ENUM -> value is JsonPrimitive && value.isString && descriptor.getElementIndex(value.content) >= 0
            StructureKind.CLASS, StructureKind.OBJECT -> value is JsonObject && value.all { (name, child) ->
                val index = descriptor.getElementIndex(name)
                index >= 0 && validValue(child, descriptor.getElementDescriptor(index), stringBudget)
            }
            StructureKind.LIST -> value is JsonArray && value.all { validValue(it, descriptor.getElementDescriptor(0), stringBudget) }
            StructureKind.MAP -> value is JsonObject && value.all { (name, child) ->
                validValue(JsonPrimitive(name), descriptor.getElementDescriptor(0), stringBudget) && validValue(child, descriptor.getElementDescriptor(1), stringBudget)
            }
            else -> false
        }
    }

    private fun parseUrl(rawUrl: String, budget: Int = MAX_ROUTE_URI_BYTES): Url? {
        if (rawUrl.encodeToByteArray().size > budget || rawUrl.any { it.code < 32 || it.code == 127 }) return null
        val raw = rawUrl.trim()
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '%') { index++; continue }
            val bytes = mutableListOf<Byte>()
            while (index < raw.length && raw[index] == '%') {
                if (index + 2 >= raw.length) return null
                val high = raw[index + 1].digitToIntOrNull(16) ?: return null
                val low = raw[index + 2].digitToIntOrNull(16) ?: return null
                bytes += (high * 16 + low).toByte()
                index += 3
            }
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
        return Url(raw)
    }

    private fun String.normalizedHost() = lowercase().trim().trimEnd('.')
    private fun String.pathParts() = split('/').filter(String::isNotEmpty)
    private fun parameterName(part: String): String? = part.takeIf { it.startsWith('{') && it.endsWith('}') }?.drop(1)?.dropLast(1)
    private inline fun <T> safely(block: () -> T?): T? = runCatching(block).getOrNull()
}
