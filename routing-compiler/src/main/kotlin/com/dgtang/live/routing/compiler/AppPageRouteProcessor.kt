package com.dgtang.live.routing.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate

class AppPageRouteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = AppPageRouteProcessor(environment)
}

private class AppPageRouteProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val registry = resolver.registry(PAGE_SPEC, environment.logger) ?: return emptyList()
        val configuration = registry.registryArguments(PAGE_SPEC)
        val pageRoute = (configuration.getValue("annotation") as KSType).sourceName()
        val symbols = resolver.getSymbolsWithAnnotation(pageRoute).toList()
        if (!registry.validate()) return (symbols + registry).distinct()
        val pageEntry = (configuration.getValue("entry") as KSType).declaration.qualifiedName!!.asString()
        val reservedRoutes = (configuration["reservedRoutes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val functions = registry.declarations.filterIsInstance<KSFunctionDeclaration>().toList()
        val open = functions.singleOrNull { it.simpleName.asString() == "open" }
        val register = functions.singleOrNull { it.simpleName.asString() == "register" }
        if (open?.parameters?.size != 2 || register?.parameters?.size != 2) {
            environment.logger.error("页面注册表需要 open(context, destination) 与 register(graph, context) 契约", registry)
            return emptyList()
        }
        val openHost = open.parameters[0].name!!.asString()
        val openDestination = open.parameters[1].name!!.asString()
        val registerGraph = register.parameters[0].name!!.asString()
        val registerContext = register.parameters[1].name!!.asString()
        val appPage = open.parameters[1].type.resolve().sourceName()
        val deferred = symbols.filterNot { it.validate() }
        if (deferred.isNotEmpty()) return (symbols + registry).distinct()
        var invalid = false
        fun reject(message: String, symbol: KSAnnotated) {
            environment.logger.error(message, symbol)
            invalid = true
        }
        val bindings = symbols.mapNotNull { symbol ->
            val property = symbol as? KSPropertyDeclaration
            if (property == null || property.parentDeclaration != null || property.qualifiedName == null ||
                property.isMutable || property.extensionReceiver != null ||
                Modifier.PRIVATE in property.modifiers || Modifier.PROTECTED in property.modifiers) {
                reject("@AppPageRoute 必须声明在可引用的顶层 internal/public val", symbol)
                return@mapNotNull null
            }
            val routeAnnotation = property.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == pageRoute
            }
            val arguments = routeAnnotation.arguments.associate { it.name?.asString() to it.value }
            val destination = arguments["destination"] as? KSType
            val destinationClass = destination?.declaration as? KSClassDeclaration
            if (destinationClass == null || destinationClass.classKind !in setOf(ClassKind.CLASS, ClassKind.OBJECT) ||
                Modifier.SEALED in destinationClass.modifiers || Modifier.ABSTRACT in destinationClass.modifiers ||
                destinationClass.getAllSuperTypes().none { it.declaration.qualifiedName?.asString() == appPage }) {
                reject("AppPageRoute.destination 必须是具体 AppDestination.AppPage", property)
                return@mapNotNull null
            }
            val type = property.type.resolve()
            val argument = type.arguments.singleOrNull()
            val boundDestination = argument?.type?.resolve()
            if (type.declaration.qualifiedName?.asString() != pageEntry || type.nullability == Nullability.NULLABLE ||
                argument?.variance != Variance.INVARIANT || boundDestination?.nullability == Nullability.NULLABLE ||
                boundDestination?.declaration?.qualifiedName?.asString() != destinationClass.qualifiedName?.asString()) {
                reject("AppPageEntry<T> 泛型必须与 AppPageRoute.destination 一致且不可为空", property)
            }
            val route = arguments["route"] as? String ?: ""
            if (route.isNotEmpty() && !Regex("[A-Za-z][A-Za-z0-9_]*").matches(route)) {
                reject("非法 AppPageRoute.route: $route", property)
            }
            if (route in reservedRoutes) {
                reject("AppPageRoute.route 占用宿主根保留名: $route", property)
            }
            PageBinding(property, destinationClass.qualifiedName!!.asString(), route)
        }
        for ((destination, duplicates) in bindings.groupBy { it.destination }.filterValues { it.size > 1 }) {
            duplicates.forEach { reject("AppPage 重复绑定: $destination", it.property) }
        }
        for ((route, duplicates) in bindings.filter { it.route.isNotEmpty() }.groupBy { it.route }.filterValues { it.size > 1 }) {
            duplicates.forEach { reject("AppPage 非空 route 重复: $route", it.property) }
        }
        val page = resolver.getClassDeclarationByName(resolver.getKSNameFromString(appPage))
        if (page == null) {
            environment.logger.error("找不到 AppDestination.AppPage")
            return emptyList()
        }
        fun requireBindings(parent: KSClassDeclaration) {
            for (child in parent.getSealedSubclasses()) {
                if (Modifier.SEALED in child.modifiers) requireBindings(child)
                else if (child.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT) &&
                    Modifier.ABSTRACT !in child.modifiers &&
                    bindings.none { it.destination == child.qualifiedName?.asString() }) {
                    reject("AppPage 具体类型缺少 @AppPageRoute 绑定: ${child.qualifiedName?.asString()}", child)
                }
            }
        }
        requireBindings(page)
        if (invalid || bindings.isEmpty()) return emptyList()
        val dependencies = Dependencies(true, *(bindings.mapNotNull { it.property.containingFile } + listOfNotNull(registry.containingFile)).distinct().toTypedArray())
        environment.codeGenerator.createNewFile(dependencies, registry.packageName.asString(), registry.simpleName.asString())
            .bufferedWriter().use { writer ->
                writer.appendLine("package ${registry.packageName.asString()}")
                writer.appendLine()
                writer.appendLine()
                writer.appendLine("// 由各 feature 的 @AppPageRoute 生成，根会话与独立直播宿主沿用原生命周期。")
                writer.appendLine("${registry.visibility()} actual object ${registry.simpleName.asString()} {")
                writer.appendLine("    actual fun open(${open.parameters.joinToString { it.name!!.asString() + ": " + it.type.resolve().sourceName() }}): ${open.returnType!!.resolve().sourceName()} {")
                writer.appendLine("        return when (${open.parameters[1].name!!.asString()}) {")
                for (binding in bindings.sortedBy { it.property.qualifiedName!!.asString() }) {
                    writer.appendLine("            is ${binding.destination} -> ${binding.property.qualifiedName!!.asString()}.open($openHost, $openDestination, \"${binding.route}\")")
                }
                writer.appendLine("        }")
                writer.appendLine("    }")
                writer.appendLine()
                writer.appendLine("    actual fun register(${register.parameters.joinToString { it.name!!.asString() + ": " + it.type.resolve().sourceName() }}) {")
                for (binding in bindings.sortedBy { it.property.qualifiedName!!.asString() }) {
                    writer.appendLine("        ${binding.property.qualifiedName!!.asString()}.register($registerGraph, $registerContext, \"${binding.route}\")")
                }
                writer.appendLine("    }")
                writer.appendLine("}")
            }
        generated = true
        return emptyList()
    }
}

private data class PageBinding(val property: KSPropertyDeclaration, val destination: String, val route: String)
