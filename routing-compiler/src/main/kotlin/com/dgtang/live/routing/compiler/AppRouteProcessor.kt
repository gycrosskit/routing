package com.dgtang.live.routing.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.validate

class AppRouteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        AppRouteProcessor(environment.codeGenerator, environment.logger)
}

private class AppRouteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val registry = resolver.registry(ROUTE_SPEC, logger) ?: return emptyList()
        val configuration = registry.registryArguments(ROUTE_SPEC)
        val annotationType = configuration.getValue("annotation") as KSType
        val annotationName = annotationType.sourceName()
        val symbols = resolver.getSymbolsWithAnnotation(annotationName).toList()
        if (!registry.validate()) return (symbols + registry).distinct()
        val destinationRoot = (configuration.getValue("destinationRoot") as KSType).sourceName()
        val targetType = (configuration.getValue("target") as KSType).sourceName()
        val wrapper = (configuration.getValue("wrapper") as KSType).declaration as KSClassDeclaration
        val wrapperType = checkNotNull(wrapper.qualifiedName).asString()
        val wrappedField = wrapper.primaryConstructor?.parameters?.singleOrNull {
            it.type.resolve().sourceName() == destinationRoot
        }?.name?.asString()
        val definition = registry.getAllProperties().singleOrNull { it.simpleName.asString() == "definitions" }
            ?.type?.resolve()?.takeIf { it.declaration.qualifiedName?.asString() == "kotlin.collections.List" }
            ?.arguments?.singleOrNull()?.type?.resolve()?.sourceName()
        val json = configuration["json"] as? String
        if (definition == null || wrappedField == null || json == null || !Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""").matches(json)) {
            logger.error("注册表需要 definitions: List<T>、唯一目的地包装字段和有效 JSON 属性名", registry)
            return emptyList()
        }
        val annotationParameters = (annotationType.declaration as KSClassDeclaration).primaryConstructor?.parameters
            ?.associateBy { it.name!!.asString() }.orEmpty()
        fun enumClassName(type: KSType?): String? = when (val declaration = type?.declaration) {
            is com.google.devtools.ksp.symbol.KSTypeAlias -> enumClassName(declaration.type.resolve())
            is KSClassDeclaration -> declaration.qualifiedName?.asString()?.takeIf { declaration.classKind == ClassKind.ENUM_CLASS }
            else -> null
        }
        val exposureType = enumClassName(annotationParameters["exposure"]?.type?.resolve())
        val accessType = enumClassName(annotationParameters["access"]?.type?.resolve())
        val featuresType = annotationParameters["features"]?.type?.resolve()
        val featureType = featuresType?.takeIf { it.declaration.qualifiedName?.asString() == "kotlin.Array" }
            ?.arguments?.singleOrNull()?.type?.resolve()?.let(::enumClassName)
        if (exposureType == null || accessType == null || featureType == null) {
            logger.error("路由注解需要 exposure/access 枚举与 features 枚举数组", registry)
            return emptyList()
        }
        val deferred = symbols.filterNot { it.validate() }
        if (deferred.isNotEmpty()) return (symbols + registry).distinct()
        var invalid = false
        fun reject(message: String, symbol: KSAnnotated) {
            logger.error(message, symbol)
            invalid = true
        }
        fun requireRoutes(parent: KSClassDeclaration) {
            for (child in parent.getSealedSubclasses()) {
                if (Modifier.SEALED in child.modifiers) {
                    requireRoutes(child)
                } else if (child.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT) &&
                    Modifier.ABSTRACT !in child.modifiers &&
                    child.annotation(annotationName) == null) {
                    reject("AppDestination 具体类型缺少 @AppRoute: ${child.qualifiedName?.asString()}", child)
                }
            }
        }
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(destinationRoot))
            ?.takeIf { it.containingFile != null && it.origin in setOf(Origin.KOTLIN, Origin.JAVA) }?.let(::requireRoutes)
        if (symbols.isEmpty()) return emptyList()
        val routes = symbols.mapNotNull { symbol ->
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null) {
                reject("@AppRoute 只能声明在 AppDestination 或 ExternalNavigationTarget 子类", symbol)
                return@mapNotNull null
            }
            val type = declaration.qualifiedName?.asString()
            val direct = declaration.getAllSuperTypes().any {
                it.declaration.qualifiedName?.asString() == targetType
            }
            if (!direct && declaration.getAllSuperTypes().none {
                    it.declaration.qualifiedName?.asString() == destinationRoot
                }) {
                reject("@AppRoute 类型必须实现 AppDestination", declaration)
            }
            val annotation = declaration.annotation(annotationName) ?: return@mapNotNull null
            val arguments = annotation.arguments.associate { it.name?.asString() to it.value }
            val serialName = declaration.annotation("kotlinx.serialization.SerialName")
                ?.arguments?.firstOrNull()?.value as? String
            val name = (arguments["name"] as? String).orEmpty().ifEmpty { serialName.orEmpty() }
            val path = arguments["path"] as? String ?: ""
            fun enumName(value: Any?): String? = (value as? KSType)?.declaration?.simpleName?.asString()
                ?: value?.toString()?.substringAfterLast('.')
            val exposure = enumName(arguments["exposure"]) ?: "INTERNAL"
            val access = enumName(arguments["access"]) ?: run {
                reject("无法解析路由 access 枚举", declaration)
                return@mapNotNull null
            }
            val allowPresentation = arguments["allowPresentation"] as? Boolean ?: false
            val features = (arguments["features"] as? List<*>)?.mapNotNull(::enumName).orEmpty()
            fun parameters(key: String): List<String> =
                (arguments[key] as? List<*>)?.filterIsInstance<String>().orEmpty()
            val publicParameters = parameters("publicParameters")
            val positiveParameters = parameters("positiveParameters")
            if (!Regex("[a-z][a-z0-9_]*").matches(name)) reject("非法路由 name: $name", declaration)
            if (!Regex("/(?:[A-Za-z0-9_-]+|\\{[A-Za-z_][A-Za-z0-9_]*})(?:/(?:[A-Za-z0-9_-]+|\\{[A-Za-z_][A-Za-z0-9_]*}))*").matches(path)) {
                reject("路由 path 必须是无 query、fragment、空段或相对段的绝对路径: $path", declaration)
            }
            if (exposure !in setOf("INTERNAL", "RENDERER", "PUBLIC")) reject("未知路由 exposure: $exposure", declaration)
            if (features.distinct().size != features.size) reject("features 存在重复功能", declaration)
            if (declaration.annotation("kotlinx.serialization.Serializable") == null) {
                reject("@AppRoute 类型必须标记 @Serializable", declaration)
            }
            if (!direct && serialName != name) reject("路由 name 必须与 @SerialName 一致: $name", declaration)
            val fields = declaration.getAllProperties()
                .filter { it.annotation("kotlinx.serialization.Transient") == null }
                .associateBy {
                    it.annotation("kotlinx.serialization.SerialName")?.arguments?.firstOrNull()?.value as? String
                        ?: it.simpleName.asString()
                }
            val placeholders = Regex("\\{([^}]+)}").findAll(path).map { it.groupValues[1] }.toList()
            for ((label, values) in listOf(
                "publicParameters" to publicParameters,
                "positiveParameters" to positiveParameters,
                "path placeholders" to placeholders,
            )) {
                if (values.distinct().size != values.size) reject("$label 存在重复字段", declaration)
                values.filterNot { it in fields }.forEach { reject("$label 字段不存在: $it", declaration) }
            }
            if (exposure == "PUBLIC") {
                placeholders.filterNot { it in publicParameters }.forEach {
                    reject("PUBLIC 路由的 path placeholder 必须在 publicParameters 中: $it", declaration)
                }
            }
            if (exposure != "INTERNAL") {
                publicParameters.forEach { name ->
                    val field = fields[name] ?: return@forEach
                    val fieldType = field.type.resolve().declaration
                    if (fieldType.qualifiedName?.asString() !in setOf(
                            "kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Boolean",
                        ) && (fieldType as? KSClassDeclaration)?.classKind != ClassKind.ENUM_CLASS) {
                        reject("publicParameters 仅支持 String、Int、Long、Boolean 和枚举: $name", field)
                    }
                }
            }
            positiveParameters.forEach { field ->
                val fieldType = fields[field]?.type?.resolve()?.declaration?.qualifiedName?.asString()
                if (fieldType != null && fieldType !in setOf("kotlin.String", "kotlin.Int", "kotlin.Long")) {
                    reject("positiveParameters 仅支持 String、Int、Long: $field", declaration)
                }
            }
            Route(declaration, type.orEmpty(), name, path, exposure, access, features, publicParameters, positiveParameters, allowPresentation, direct)
        }
        for (selector in listOf<(Route) -> String>({ it.name }, { it.path })) {
            routes.groupBy(selector).filterValues { it.size > 1 }.forEach { (value, conflicts) ->
                conflicts.forEach { reject("重复路由 name/path: $value", it.declaration) }
            }
        }
        for ((index, route) in routes.withIndex()) {
            val segments = route.path.split('/')
            for (other in routes.drop(index + 1)) {
                val otherSegments = other.path.split('/')
                if (route.path != other.path && segments.size == otherSegments.size &&
                    segments.zip(otherSegments).all { (left, right) ->
                        left == right || left.startsWith('{') || right.startsWith('{')
                    }) {
                    reject("路由 path 匹配范围重叠: ${route.path} 与 ${other.path}", other.declaration)
                }
            }
        }
        if (invalid) return emptyList()
        val dependencies = Dependencies(true, *(routes.mapNotNull { it.declaration.containingFile } + listOfNotNull(registry.containingFile)).distinct().toTypedArray())
        codeGenerator.createNewFile(dependencies, registry.packageName.asString(), registry.simpleName.asString())
            .bufferedWriter().use { writer ->
                writer.appendLine("package ${registry.packageName.asString()}")
                writer.appendLine()
                writer.appendLine("import kotlinx.serialization.json.JsonObject")
                writer.appendLine()
                writer.appendLine("// 由 @AppRoute 编译生成；路由准入和平台宿主仍由现有导航链负责。")
                writer.appendLine("${registry.visibility()} actual object ${registry.simpleName.asString()} {")
                writer.appendLine("    actual val definitions: List<$definition> = listOf(")
                for (route in routes.sortedBy { it.name }) {
                    val type = route.type
                    writer.appendLine("        $definition(")
                    writer.appendLine("            name = ${route.name.quoted()},")
                    writer.appendLine("            path = ${route.path.quoted()},")
                    writer.appendLine("            exposure = $exposureType.${route.exposure},")
                    writer.appendLine("            access = $accessType.${route.access},")
                    writer.appendLine("            features = setOf(${route.features.joinToString { "$featureType.$it" }}),")
                    writer.appendLine("            publicParameters = setOf(${route.publicParameters.joinToString { it.quoted() }}),")
                    writer.appendLine("            positiveParameters = setOf(${route.positiveParameters.joinToString { it.quoted() }}),")
                    writer.appendLine("            allowPresentation = ${route.allowPresentation},")
                    writer.appendLine("            descriptor = $type.serializer().descriptor,")
                    val decode = "$json.decodeFromJsonElement($type.serializer(), it)"
                    writer.appendLine("            decode = { ${if (route.direct) decode else "$wrapperType($wrappedField = $decode)"} },")
                    val target = if (route.direct) "target as? $type" else "(target as? $wrapperType)?.$wrappedField as? $type"
                    writer.appendLine("            encode = { target -> ($target)?.let { $json.encodeToJsonElement($type.serializer(), it) as JsonObject } },")
                    writer.appendLine("        ),")
                }
                writer.appendLine("    )")
                writer.appendLine("}")
            }
        generated = true
        return emptyList()
    }
}

private data class Route(
    val declaration: KSClassDeclaration,
    val type: String,
    val name: String,
    val path: String,
    val exposure: String,
    val access: String,
    val features: List<String>,
    val publicParameters: List<String>,
    val positiveParameters: List<String>,
    val allowPresentation: Boolean,
    val direct: Boolean,
)

private fun KSAnnotated.annotation(name: String): KSAnnotation? =
    annotations.firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == name }

private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
