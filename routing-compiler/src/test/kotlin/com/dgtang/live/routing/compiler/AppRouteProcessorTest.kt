package com.dgtang.live.routing.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppRouteProcessorTest {
    @Test
    fun generatesDestinationAndLiveWithExplicitExposure() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("search")
                @AppRoute("search", "/search/{keyword}", AppRouteExposure.PUBLIC, ["keyword"])
                data class Search(val keyword: String) : AppDestination
                @Serializable @SerialName("about") @AppRoute("about", "/about")
                data object About : AppDestination
            }
            sealed interface ExternalNavigationTarget {
                data class Destination(val value: AppDestination) : ExternalNavigationTarget
                @Serializable @AppRoute("live_room", "/live/{businessRoomId}", AppRouteExposure.PUBLIC,
                    ["businessRoomId"], ["businessRoomId"])
                data class Live(val businessRoomId: Int) : ExternalNavigationTarget
            }
        """, targets = "")
        assertEquals("OK", result.exit)
        assertEquals(emptyList(), result.errors)
        assertContains(result.generated, "name = \"search\"")
        assertContains(result.generated, "exposure = com.dgtang.live.routing.RouteExposure.PUBLIC")
        assertContains(result.generated, "exposure = com.dgtang.live.routing.RouteExposure.INTERNAL")
        assertContains(result.generated, "publicParameters = setOf(\"keyword\")")
        assertContains(result.generated, "positiveParameters = setOf(\"businessRoomId\")")
        assertContains(result.generated, "ExternalNavigationTarget.Destination(value = com.dgtang.live.data.remote.AppJson.decodeFromJsonElement(")
        assertContains(result.generated, "target as? com.dgtang.live.navigation.ExternalNavigationTarget.Live")
        assertTrue(result.generated.indexOf("name = \"about\"") < result.generated.indexOf("name = \"search\""))
    }

    @Test
    fun derivesStableNameAndGeneratesStaticAccessAndFeatures() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("news")
                @AppRoute(path="/news", access=NavigationAccess.GUEST_OR_MEMBER, features=[AppFeature.NEWS], allowPresentation=true)
                data class News(val title: String = "") : AppDestination
                @Serializable @SerialName("about") @AppRoute(path="/about")
                data object About : AppDestination
            }
            sealed interface ExternalNavigationTarget {
                data class Destination(val value: AppDestination) : ExternalNavigationTarget
                @Serializable @AppRoute(name="live_room", path="/live", access=NavigationAccess.SHOP_BOUND,
                    features=[AppFeature.LIVE])
                data class Live(val businessRoomId: Int) : ExternalNavigationTarget
            }
        """, targets = "")
        assertEquals("OK", result.exit)
        assertContains(result.generated, "name = \"news\"")
        assertContains(result.generated, "name = \"live_room\"")
        assertContains(result.generated, "access = com.dgtang.live.navigation.NavigationAccess.GUEST_OR_MEMBER")
        assertContains(result.generated, "access = com.dgtang.live.navigation.NavigationAccess.AUTHENTICATED")
        assertContains(result.generated, "access = com.dgtang.live.navigation.NavigationAccess.SHOP_BOUND")
        assertContains(result.generated, "features = setOf(com.dgtang.live.data.config.AppFeature.NEWS)")
        assertContains(result.generated, "allowPresentation = true")
        assertContains(result.generated, "features = setOf(com.dgtang.live.data.config.AppFeature.LIVE)")
    }

    @Test
    fun rejectsMissingStableNameAndDuplicateFeatures() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @AppRoute(path="/unnamed")
                data object Unnamed : AppDestination
                @Serializable @SerialName("news") @AppRoute(path="/news", features=[AppFeature.NEWS, AppFeature.NEWS])
                data object News : AppDestination
            }
        """)
        assertRejected(result, "非法路由 name")
        assertTrue(result.errors.any { "features 存在重复功能" in it })
    }

    @Test
    fun rejectsDuplicateNamesAndPaths() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("about") @AppRoute("about", "/about")
                data object First : AppDestination
                @Serializable @SerialName("about") @AppRoute("about", "/about")
                data object Second : AppDestination
            }
        """)
        assertRejected(result, "重复路由 name/path: about")
        assertTrue(result.errors.any { "/about" in it })
    }

    @Test
    fun rejectsOverlappingPlaceholdersAndLiteralPaths() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("first") @AppRoute("first", "/news/{id}")
                data class First(val id: String) : AppDestination
                @Serializable @SerialName("second") @AppRoute("second", "/news/{key}")
                data class Second(val key: String) : AppDestination
                @Serializable @SerialName("latest") @AppRoute("latest", "/news/latest")
                data object Latest : AppDestination
            }
        """)
        assertRejected(result, "路由 path 匹配范围重叠: /news/{id} 与 /news/{key}")
        assertTrue(result.errors.any { "/news/latest" in it })
    }

    @Test
    fun rejectsInvalidPathAndUnknownFields() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("bad")
                @AppRoute("bad", "/../bad?token=x", publicParameters=["missing", "missing"],
                    positiveParameters=["absent"])
                data object Bad : AppDestination
            }
        """)
        assertRejected(result, "路由 path 必须")
        assertTrue(result.errors.any { "publicParameters 存在重复字段" in it })
        assertTrue(result.errors.any { "字段不存在: missing" in it })
        assertTrue(result.errors.any { "字段不存在: absent" in it })
    }

    @Test
    fun rejectsMissingPlaceholderAndUnsupportedPositiveType() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("bad")
                @AppRoute("bad", "/bad/{missing}", positiveParameters=["enabled"])
                data class Bad(val enabled: Boolean) : AppDestination
            }
        """)
        assertRejected(result, "path placeholders 字段不存在: missing")
        assertTrue(result.errors.any { "仅支持 String、Int、Long" in it })
    }

    @Test
    fun rejectsUnlistedPublicPlaceholderAndUnsupportedPublicFields() {
        val result = process("""
            @Serializable data class Payload(val value: String)
            sealed interface AppDestination {
                @Serializable @SerialName("bad")
                @AppRoute("bad", "/bad/{id}", AppRouteExposure.PUBLIC,
                    publicParameters=["payload", "items"])
                data class Bad(val id: String, val payload: Payload, val items: List<String>) : AppDestination
                @Serializable @SerialName("renderer")
                @AppRoute("renderer", "/renderer", AppRouteExposure.RENDERER, publicParameters=["payload"])
                data class Renderer(val payload: Payload) : AppDestination
            }
        """)
        assertRejected(result, "path placeholder 必须在 publicParameters 中: id")
        assertEquals(2, result.errors.count { "publicParameters 仅支持" in it && "payload" in it })
        assertTrue(result.errors.any { "publicParameters 仅支持" in it && "items" in it })
    }

    @Test
    fun permitsPublicScalarAndEnumFieldsAndInternalNestedPayload() {
        val result = process("""
            @Serializable data class Payload(val values: List<String>)
            @Serializable enum class Category { @SerialName("first") FIRST, SECOND }
            sealed interface AppDestination {
                @Serializable @SerialName("public")
                @AppRoute("public", "/public/{category}", AppRouteExposure.PUBLIC,
                    publicParameters=["category", "text", "count", "total", "enabled"])
                data class Public(@SerialName("category") val kind: Category, val text: String? = null, val count: Int = 0,
                    val total: Long = 0, val enabled: Boolean = false) : AppDestination
                @Serializable @SerialName("internal") @AppRoute("internal", "/internal")
                data class Internal(val payload: Payload) : AppDestination
            }
        """)
        assertEquals("OK", result.exit)
        assertEquals(emptyList(), result.errors)
        assertContains(result.generated, "name = \"public\"")
        assertContains(result.generated, "name = \"internal\"")
    }

    @Test
    fun rejectsSerialNameMismatchAndUnrelatedTypes() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("old") @AppRoute("new", "/new")
                data object Bad : AppDestination
            }
            @Serializable @SerialName("other") @AppRoute("other", "/other")
            data object Other
        """)
        assertRejected(result, "name 必须与 @SerialName 一致")
        assertTrue(result.errors.any { "必须实现 AppDestination" in it })
    }

    @Test
    fun rejectsMissingSerialization() {
        val result = process("""
            sealed interface AppDestination {
                @SerialName("about") @AppRoute("about", "/about")
                data object About : AppDestination
            }
        """)
        assertRejected(result, "必须标记 @Serializable")
    }

    @Test
    fun rejectsUnregisteredConcreteClassesWithoutAnyAnnotations() {
        val result = process("""
            sealed interface AppDestination {
                sealed interface Root : AppDestination
                sealed interface AppPage : AppDestination
                @Serializable data object ForgottenRoot : Root
                @Serializable data class ForgottenPage(val id: Int) : AppPage
            }
        """)
        assertRejected(result, "具体类型缺少 @AppRoute: com.dgtang.live.navigation.AppDestination.ForgottenRoot")
        assertTrue(result.errors.any { "AppDestination.ForgottenPage" in it })
        assertEquals(2, result.errors.size)
    }

    @Test
    fun rejectsUnregisteredSiblingOfAnAnnotatedDestination() {
        val result = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("about") @AppRoute("about", "/about")
                data object About : AppDestination
                @Serializable data object Forgotten : AppDestination
            }
        """)
        assertRejected(result, "具体类型缺少 @AppRoute: com.dgtang.live.navigation.AppDestination.Forgotten")
    }

    @Test
    fun permitsUnannotatedSealedParentsWithRegisteredConcreteChildren() {
        val result = process("""
            sealed interface AppDestination {
                sealed interface Root : AppDestination
                sealed interface AppPage : AppDestination
                @Serializable @SerialName("main") @AppRoute("main", "/home")
                data object Main : Root
                @Serializable @SerialName("search") @AppRoute("search", "/search")
                data class Search(val keyword: String) : AppPage
            }
        """)
        assertEquals("OK", result.exit)
        assertEquals(emptyList(), result.errors)
        assertEquals(2, Regex("AppRouteDefinition\\(").findAll(result.generated).count())
    }

    @Test
    fun permitsInternalPositiveStringAndProducesNothingWithoutAnnotations() {
        val positive = process("""
            sealed interface AppDestination {
                @Serializable @SerialName("appointment")
                @AppRoute("appointment", "/appointment", positiveParameters=["roomId"])
                data class Appointment(val roomId: String) : AppDestination
            }
        """)
        assertEquals("OK", positive.exit)
        assertContains(positive.generated, "positiveParameters = setOf(\"roomId\")")
        val empty = process("sealed interface AppDestination")
        assertEquals("OK", empty.exit)
        assertEquals("", empty.generated)
    }

    private fun assertRejected(result: Result, message: String) {
        assertEquals("PROCESSING_ERROR", result.exit)
        assertTrue(result.errors.any { message in it }, result.errors.joinToString())
        assertFalse(result.generated.isNotEmpty())
    }

    private data class Result(val exit: String, val errors: List<String>, val generated: String)

    private fun process(declarations: String, targets: String = """
        sealed interface ExternalNavigationTarget {
            data class Destination(val value: AppDestination) : ExternalNavigationTarget
        }
    """): Result {
        val result = runKspSources(mapOf(
            "Serialization.kt" to """
                package kotlinx.serialization
                annotation class Serializable
                annotation class SerialName(val value: String)
                annotation class Transient
            """,
            "Registry.kt" to """
                package com.dgtang.live.navigation
                import com.dgtang.live.routing.RouteRegistrySpec
                class AppRouteDefinition
                @RouteRegistrySpec(AppRoute::class, AppDestination::class,
                    ExternalNavigationTarget::class, ExternalNavigationTarget.Destination::class,
                    "com.dgtang.live.data.remote.AppJson")
                internal expect object GeneratedAppRoutes { val definitions: List<AppRouteDefinition> }
            """,
            "Features.kt" to """
                package com.dgtang.live.data.config
                enum class AppFeature { LIVE, NEWS }
            """,
            "Routes.kt" to """
                package com.dgtang.live.navigation
                import com.dgtang.live.data.config.AppFeature
                import kotlinx.serialization.Serializable
                import kotlinx.serialization.SerialName
                typealias AppRouteExposure = com.dgtang.live.routing.RouteExposure
                enum class NavigationAccess { PUBLIC, GUEST_OR_MEMBER, AUTHENTICATED, SHOP_BOUND }
                annotation class AppRoute(val name: String = "", val path: String,
                    val exposure: AppRouteExposure = AppRouteExposure.INTERNAL,
                    val publicParameters: Array<String> = [], val positiveParameters: Array<String> = [],
                    val access: NavigationAccess = NavigationAccess.AUTHENTICATED, val features: Array<AppFeature> = [], val allowPresentation: Boolean = false)
                $declarations
                $targets
            """,
        ), listOf(AppRouteProcessorProvider()))
        return Result(result.exit, result.errors,
            result.outputs["com/dgtang/live/navigation/GeneratedAppRoutes.kt"].orEmpty())
    }
}
