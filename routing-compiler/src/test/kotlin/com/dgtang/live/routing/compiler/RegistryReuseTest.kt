package com.dgtang.live.routing.compiler

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 换宿主类型、枚举、包装字段与保留名，验证生成器不再依赖本 App。 */
class RegistryReuseTest {
    @Test
    fun routeRegistryUsesForeignDefaultsAndWrapperPayload() {
        val result = foreignRoutes(consume = true)
        assertEquals("OK", result.exit, result.errors.joinToString())
        val missing = foreignRoutes("data object Forgotten : Screen")
        assertEquals("PROCESSING_ERROR", missing.exit)
        assertTrue(missing.errors.any { "Forgotten" in it })
    }

    @Test
    fun routeAggregationRetainsResolvedEntriesAcrossRounds() {
        val result = foreignRoutes("""
            @Serializable @SerialName("later") @Link(path = "/later")
            data class Later(val payload: GeneratedPayload) : Screen
        """, generated = true)
        assertEquals("OK", result.exit, result.errors.joinToString())
        val table = result.outputs.getValue("sample/catalog/GeneratedLinks.kt")
        assertContains(table, "name = \"archive\"")
        assertContains(table, "name = \"later\"")
    }

    @Test
    fun malformedAnnotationReportsAnErrorAtTheRegistry() {
        val result = foreignRoutes(annotation = "Deprecated")
        assertEquals("PROCESSING_ERROR", result.exit)
        assertTrue(result.errors.any { "路由注解需要" in it })
    }

    private fun foreignRoutes(extra: String = "", generated: Boolean = false, annotation: String = "Link",
        consume: Boolean = false) = runKspSources(mapOf(
        "Routes.kt" to """
            package sample.catalog
            import com.dgtang.live.routing.RouteExposure
            import com.dgtang.live.routing.RouteRegistrySpec
            import com.dgtang.live.routing.RouteDefinition
            import com.dgtang.live.routing.RouteRegistry
            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.descriptors.SerialDescriptor
            import kotlinx.serialization.descriptors.buildClassSerialDescriptor
            import kotlinx.serialization.encoding.Decoder
            import kotlinx.serialization.encoding.Encoder
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.json.JsonObject
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.SerialName
            enum class Policy { OPEN, SIGNED_IN }
            enum class Ability { ARCHIVE }
            annotation class Link(val name: String = "", val path: String,
                val exposure: RouteExposure = RouteExposure.INTERNAL,
                val publicParameters: Array<String> = [], val positiveParameters: Array<String> = [],
                val access: Policy = Policy.SIGNED_IN, val features: Array<Ability> = [],
                val allowPresentation: Boolean = false)
            sealed interface Screen {
                @Serializable(with = ArchiveSerializer::class) @SerialName("archive")
                @Link(path = "/archive", exposure = RouteExposure.PUBLIC, features = [Ability.ARCHIVE])
                data object Archive : Screen { fun serializer(): KSerializer<Archive> = ArchiveSerializer }
                $extra
            }
            interface Target
            data class Envelope(val payload: Screen, val title: String = "") : Target
            class Definition(
                name: String, path: String, exposure: RouteExposure,
                val access: Policy, val features: Set<Ability>, val allowPresentation: Boolean,
                publicParameters: Set<String>, positiveParameters: Set<String>, descriptor: SerialDescriptor,
                decode: (JsonObject) -> Target, encode: (Target) -> JsonObject?,
            ) : RouteDefinition<Target>(name, path, exposure, publicParameters, positiveParameters, descriptor, decode, encode)
            val WireJson = Json
            object ArchiveSerializer : KSerializer<Screen.Archive> {
                override val descriptor = buildClassSerialDescriptor("archive")
                override fun serialize(encoder: Encoder, value: Screen.Archive) {
                    encoder.beginStructure(descriptor).endStructure(descriptor)
                }
                override fun deserialize(decoder: Decoder): Screen.Archive {
                    decoder.beginStructure(descriptor).endStructure(descriptor)
                    return Screen.Archive
                }
            }
            @RouteRegistrySpec($annotation::class, Screen::class, Target::class, Envelope::class, "sample.catalog.WireJson")
            internal expect object GeneratedLinks { val definitions: List<Definition> }
            fun verifyConsumer() {
                val definition = GeneratedLinks.definitions.single()
                check(definition.access == Policy.SIGNED_IN && definition.features == setOf(Ability.ARCHIVE))
                val registry = RouteRegistry(GeneratedLinks.definitions)
                val target = Envelope(Screen.Archive)
                check(registry.parsePublic("catalog.test", "https://catalog.test/archive") == target)
                check(registry.publicUri("catalog.test", target) == "https://catalog.test/archive")
                val envelope = checkNotNull(registry.encodePublicTarget(target))
                check(registry.decodePublicTarget("archive", envelope.getValue("arguments") as JsonObject) == target)
            }
        """,
    ), listOf(AppRouteProcessorProvider()) + if (generated) listOf(payloadGenerator()) else emptyList(),
        libraries = consumerLibraries(), consumer = if (consume) "sample.catalog.RoutesKt" else null)

    @Test
    fun pageRegistryUsesForeignSignaturesAndOnlyConfiguredReservedNames() {
        val result = foreignPages("Main", consume = true)
        assertEquals("OK", result.exit, result.errors.joinToString())
        val reserved = foreignPages("Landing")
        assertEquals("PROCESSING_ERROR", reserved.exit)
        assertTrue(reserved.errors.any { "保留名" in it })
        val missing = foreignPages("Main", "data object Forgotten : Node")
        assertEquals("PROCESSING_ERROR", missing.exit)
        assertTrue(missing.errors.any { "Forgotten" in it })
    }

    private fun payloadGenerator(
        packageName: String = "sample.catalog",
        declaration: String = "class GeneratedPayload",
    ): SymbolProcessorProvider = object : SymbolProcessorProvider {
        override fun create(environment: com.google.devtools.ksp.processing.SymbolProcessorEnvironment): SymbolProcessor =
            object : SymbolProcessor {
                private var generated = false
                override fun process(resolver: com.google.devtools.ksp.processing.Resolver): List<com.google.devtools.ksp.symbol.KSAnnotated> {
                    if (!generated) {
                        environment.codeGenerator.createNewFile(Dependencies(false), packageName, "GeneratedFixture")
                            .bufferedWriter().use { it.write("package $packageName; $declaration") }
                        generated = true
                    }
                    return emptyList()
                }
            }
    }

    @Test
    fun pageAggregationRetainsResolvedEntriesAcrossRounds() {
        val result = foreignPages("Main", "data object Later : Node",
            entries = """@BindScreen(Screen.Later::class, "Later") val later = laterEntry()""",
            providers = listOf(payloadGenerator("sample.ui", "fun laterEntry() = Entry<Screen.Later>()")))
        assertEquals("OK", result.exit, result.errors.joinToString())
        val table = result.outputs.getValue("sample/ui/GeneratedScreens.kt")
        assertContains(table, "sample.ui.about.open")
        assertContains(table, "sample.ui.later.open")
    }

    private fun foreignPages(route: String, extra: String = "", entries: String = "",
        providers: List<SymbolProcessorProvider> = emptyList(), consume: Boolean = false) = runKspSources(mapOf(
        "Pages.kt" to """
            package sample.ui
            import com.dgtang.live.routing.PageRegistrySpec
            import kotlin.reflect.KClass
            sealed interface Screen {
                sealed interface Node : Screen
                data object About : Node
                $extra
            }
            annotation class BindScreen(val destination: KClass<out Screen.Node>, val route: String)
            class OpenContext { var opened: String? = null }
            class Graph { val routes = mutableListOf<String>() }
            class GraphContext
            data class Outcome(val route: String)
            class Entry<T : Screen.Node> {
                fun open(ctx: OpenContext, value: T, route: String): Outcome {
                    ctx.opened = route
                    return Outcome(route)
                }
                fun register(tree: Graph, ctx: GraphContext, route: String) { tree.routes += route }
            }
            @BindScreen(Screen.About::class, "$route")
            val about = Entry<Screen.About>()
            $entries
            @PageRegistrySpec(BindScreen::class, Entry::class, ["Landing"])
            internal expect object GeneratedScreens {
                fun open(ctx: OpenContext, value: Screen.Node): Outcome
                fun register(tree: Graph, ctx: GraphContext)
            }
            fun verifyConsumer() {
                val context = OpenContext()
                check(GeneratedScreens.open(context, Screen.About) == Outcome("$route"))
                check(context.opened == "$route")
                val graph = Graph()
                GeneratedScreens.register(graph, GraphContext())
                check(graph.routes == listOf("$route"))
            }
        """,
    ), listOf(AppPageRouteProcessorProvider()) + providers,
        libraries = consumerLibraries(), consumer = if (consume) "sample.ui.PagesKt" else null)

    private fun consumerLibraries(): List<File> = checkNotNull(System.getProperty("routingConsumerClasspath"))
        .split(File.pathSeparator).map(::File)
}
