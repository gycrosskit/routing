package com.dgtang.live.routing.compiler

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability

internal const val ROUTE_SPEC = "com.dgtang.live.routing.RouteRegistrySpec"
internal const val PAGE_SPEC = "com.dgtang.live.routing.PageRegistrySpec"

internal fun Resolver.registry(spec: String, logger: KSPLogger): KSClassDeclaration? {
    val symbols = getSymbolsWithAnnotation(spec).toList()
    if (symbols.isEmpty()) return null
    val declaration = symbols.singleOrNull() as? KSClassDeclaration
    if (declaration == null || declaration.classKind != ClassKind.OBJECT || Modifier.EXPECT !in declaration.modifiers) {
        logger.error("每种注册表配置必须唯一声明在 expect object", symbols.first())
        return null
    }
    return declaration
}

internal fun KSAnnotated.registryArguments(spec: String): Map<String, Any?> =
    annotations.first { it.annotationType.resolve().declaration.qualifiedName?.asString() == spec }
        .arguments.associate { it.name!!.asString() to it.value }

internal fun KSType.sourceName(): String {
    val declaration = declaration
    if (declaration is KSTypeAlias) return declaration.type.resolve().sourceName()
    val name = checkNotNull(declaration.qualifiedName).asString()
    val arguments = arguments.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") {
        it.type?.resolve()?.sourceName() ?: "*"
    }.orEmpty()
    return name + arguments + if (nullability == Nullability.NULLABLE) "?" else ""
}

internal fun KSClassDeclaration.visibility(): String = if (Modifier.INTERNAL in modifiers) "internal" else "public"
