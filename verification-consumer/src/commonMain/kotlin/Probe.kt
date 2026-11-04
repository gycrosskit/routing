package sample.routing

import com.dgtang.live.routing.*
import kotlin.reflect.KClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class Access { OPEN, SIGNED_IN }
enum class Feature { ARCHIVE }
@kotlin.annotation.Target(AnnotationTarget.CLASS)
annotation class Link(val name: String = "", val path: String,
    val exposure: RouteExposure = RouteExposure.INTERNAL,
    val publicParameters: Array<String> = [], val positiveParameters: Array<String> = [],
    val access: Access = Access.SIGNED_IN, val features: Array<Feature> = [],
    val allowPresentation: Boolean = false)
sealed interface Screen {
    @Serializable @SerialName("archive")
    @Link(path = "/archive", exposure = RouteExposure.PUBLIC, features = [Feature.ARCHIVE])
    data object Archive : Screen
}
interface Target
data class Envelope(val payload: Screen) : Target
class Definition(name: String, path: String, exposure: RouteExposure,
    val access: Access, val features: Set<Feature>, val allowPresentation: Boolean,
    publicParameters: Set<String>, positiveParameters: Set<String>, descriptor: SerialDescriptor,
    decode: (JsonObject) -> Target, encode: (Target) -> JsonObject?,
) : RouteDefinition<Target>(name, path, exposure, publicParameters, positiveParameters, descriptor, decode, encode)
val WireJson = Json
@RouteRegistrySpec(Link::class, Screen::class, Target::class, Envelope::class, "sample.routing.WireJson")
internal expect object GeneratedLinks { val definitions: List<Definition> }

@kotlin.annotation.Target(AnnotationTarget.PROPERTY)
annotation class BindScreen(val destination: KClass<out Screen>, val route: String)
class OpenContext { var opened: String? = null }
class Graph { val routes = mutableListOf<String>() }
class GraphContext
data class Outcome(val route: String)
class Entry<T : Screen> {
    fun open(ctx: OpenContext, value: T, route: String): Outcome {
        ctx.opened = route
        return Outcome(route)
    }
    fun register(tree: Graph, ctx: GraphContext, route: String) { tree.routes += route }
}
@BindScreen(Screen.Archive::class, "Archive")
val archive = Entry<Screen.Archive>()
@PageRegistrySpec(BindScreen::class, Entry::class, ["Landing"])
internal expect object GeneratedScreens {
    fun open(ctx: OpenContext, value: Screen): Outcome
    fun register(tree: Graph, ctx: GraphContext)
}

fun verifyConsumer() {
    val registry = RouteRegistry(GeneratedLinks.definitions)
    val target = Envelope(Screen.Archive)
    check(registry.parsePublic("catalog.test", "https://catalog.test/archive") == target)
    check(registry.publicUri("catalog.test", target) == "https://catalog.test/archive")
    check(GeneratedLinks.definitions.single().access == Access.SIGNED_IN)
    check(GeneratedScreens.open(OpenContext(), Screen.Archive) == Outcome("Archive"))
    val graph = Graph()
    GeneratedScreens.register(graph, GraphContext())
    check(graph.routes == listOf("Archive"))
}
