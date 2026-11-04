package com.dgtang.live.routing.compiler

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppPageRouteProcessorTest {
    @Test
    fun generatesTypedOpenAndGraphRegistration() {
        val result = process("""
            @AppPageRoute(AppDestination.Search::class, "Search")
            internal val searchEntry = AppPageEntry<AppDestination.Search>()
            @AppPageRoute(AppDestination.About::class, "About")
            internal val aboutEntry = AppPageEntry<AppDestination.About>()
        """)
        assertEquals("OK", result.exit)
        assertEquals(emptyList(), result.errors)
        val generated = result.generated
        assertContains(generated, "destination: com.dgtang.live.navigation.AppDestination.AppPage): com.dgtang.live.navigation.RouteResult")
        assertContains(generated, "return when (destination)")
        assertContains(generated, "is com.dgtang.live.navigation.AppDestination.About -> feature.aboutEntry.open(host, destination, \"About\")")
        assertContains(generated, "feature.searchEntry.register(graph, context, \"Search\")")
        assertTrue(generated.indexOf("feature.aboutEntry.register") < generated.indexOf("feature.searchEntry.register"))
    }

    @Test
    fun rejectsMissingBindingsEvenWhenNoEntryIsAnnotated() {
        for (entries in listOf("", """
            @AppPageRoute(AppDestination.About::class, "About")
            internal val aboutEntry = AppPageEntry<AppDestination.About>()
        """)) {
            val result = process(entries)
            assertRejected(result, "AppPage 具体类型缺少 @AppPageRoute 绑定: com.dgtang.live.navigation.AppDestination.Search")
        }
    }

    @Test
    fun rejectsDuplicateDestinationAndNonEmptyRoute() {
        val duplicateDestination = process("""
            @AppPageRoute(AppDestination.About::class, "About") val aboutEntry = AppPageEntry<AppDestination.About>()
            @AppPageRoute(AppDestination.About::class, "AboutAlias") val anotherEntry = AppPageEntry<AppDestination.About>()
            @AppPageRoute(AppDestination.Search::class, "Search") val searchEntry = AppPageEntry<AppDestination.Search>()
        """)
        assertRejected(duplicateDestination, "AppPage 重复绑定")
        val duplicateRoute = process("""
            @AppPageRoute(AppDestination.About::class, "Page") val aboutEntry = AppPageEntry<AppDestination.About>()
            @AppPageRoute(AppDestination.Search::class, "Page") val searchEntry = AppPageEntry<AppDestination.Search>()
        """)
        assertRejected(duplicateRoute, "AppPage 非空 route 重复: Page")
    }

    @Test
    fun permitsMultipleEmptyRoutesForActions() {
        val result = process("""
            @AppPageRoute(AppDestination.About::class) val aboutEntry = AppPageEntry<AppDestination.About>()
            @AppPageRoute(AppDestination.Search::class) val searchEntry = AppPageEntry<AppDestination.Search>()
        """)
        assertEquals("OK", result.exit)
        assertContains(result.generated, "feature.aboutEntry.register(graph, context, \"\")")
        assertContains(result.generated, "feature.searchEntry.register(graph, context, \"\")")
    }

    @Test
    fun rejectsHostRootRoutesAndNullableEntries() {
        for (route in listOf("Splash", "Login", "LoginPrompt", "Main", "Audience")) {
            val result = process("""
                @AppPageRoute(AppDestination.About::class, "$route") val aboutEntry = AppPageEntry<AppDestination.About>()
                @AppPageRoute(AppDestination.Search::class, "Search") val searchEntry = AppPageEntry<AppDestination.Search>()
            """)
            assertRejected(result, "占用宿主根保留名: $route")
        }
        val nullable = process("""
            @AppPageRoute(AppDestination.About::class, "About") val aboutEntry: AppPageEntry<AppDestination.About>? = null
            @AppPageRoute(AppDestination.Search::class, "Search") val searchEntry = AppPageEntry<AppDestination.Search>()
        """)
        assertRejected(nullable, "AppPageEntry<T> 泛型必须与 AppPageRoute.destination 一致且不可为空")
    }

    @Test
    fun rejectsMismatchedEntryTypeAndUnsafeRoute() {
        val result = process("""
            @AppPageRoute(AppDestination.About::class, "../About") val aboutEntry = AppPageEntry<AppDestination.Search>()
            @AppPageRoute(AppDestination.Search::class, "Search") val searchEntry = AppPageEntry<AppDestination.Search>()
        """)
        assertRejected(result, "AppPageEntry<T> 泛型必须与 AppPageRoute.destination 一致")
        assertTrue(result.errors.any { "非法 AppPageRoute.route" in it })
    }

    @Test
    fun rejectsPrivateMemberMutableAndExtensionEntries() {
        for (entry in listOf(
            "@AppPageRoute(AppDestination.About::class) private val aboutEntry = AppPageEntry<AppDestination.About>()",
            "class Entries { @AppPageRoute(AppDestination.About::class) val aboutEntry = AppPageEntry<AppDestination.About>() }",
            "@AppPageRoute(AppDestination.About::class) var aboutEntry = AppPageEntry<AppDestination.About>()",
            "@AppPageRoute(AppDestination.About::class) val String.aboutEntry: AppPageEntry<AppDestination.About> get() = AppPageEntry()",
        )) {
            val result = process(entry + "\n@AppPageRoute(AppDestination.Search::class) val searchEntry = AppPageEntry<AppDestination.Search>()")
            assertRejected(result, "必须声明在可引用的顶层 internal/public val")
        }
    }

    @Test
    fun rejectsSealedDestinationBinding() {
        val result = process("""
            @AppPageRoute(AppDestination.AppPage::class) val pageEntry = AppPageEntry<AppDestination.AppPage>()
        """)
        assertRejected(result, "destination 必须是具体 AppDestination.AppPage")
    }

    @Test
    fun binaryDestinationsDoNotRequireSourceAppRouteAndStillRequireEveryPageBinding() {
        val workspace = File("build/ksp-test-workspaces").apply { mkdirs() }
        val root = Files.createTempDirectory(workspace.toPath(), "binary-destination-").toFile().absoluteFile
        try {
            val source = root.resolve("AppDestination.java")
            source.writeText("""
                package com.dgtang.live.navigation;
                public sealed interface AppDestination permits AppDestination.AppPage {
                    sealed interface AppPage extends AppDestination permits About, Search {}
                    final class About implements AppPage {}
                    final class Search implements AppPage {}
                }
            """.trimIndent())
            val binary = root.resolve("classes").apply { mkdirs() }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", binary.absolutePath, source.absolutePath))
            val shared = runKspSources(mapOf("Empty.kt" to "package feature"),
                listOf(AppRouteProcessorProvider()), listOf(binary))
            assertEquals("OK", shared.exit, shared.errors.joinToString())
            assertEquals(emptyList(), shared.errors)
            assertEquals(emptyMap(), shared.outputs)
            val entries = """
                @AppPageRoute(AppDestination.About::class, "About") val aboutEntry = AppPageEntry<AppDestination.About>()
                @AppPageRoute(AppDestination.Search::class, "Search") val searchEntry = AppPageEntry<AppDestination.Search>()
            """
            val ui = process(entries, destinations = "", libraries = listOf(binary))
            assertEquals("OK", ui.exit, ui.errors.joinToString())
            assertContains(ui.generated, "feature.searchEntry.open")
            val missing = process("", destinations = "", libraries = listOf(binary))
            assertRejected(missing, "AppPage 具体类型缺少 @AppPageRoute 绑定")
        } finally {
            root.deleteRecursively()
        }
    }

    private val KspTestResult.generated: String
        get() = outputs["com/dgtang/live/kuikly/core/ui/GeneratedAppPageRoutes.kt"].orEmpty()

    private fun assertRejected(result: KspTestResult, message: String) {
        assertEquals("PROCESSING_ERROR", result.exit)
        assertTrue(result.errors.any { message in it }, result.errors.joinToString())
        assertEquals("", result.generated)
    }

    private fun process(
        entries: String,
        destinations: String = """
            package com.dgtang.live.navigation
            sealed interface AppDestination {
                sealed interface AppPage : AppDestination
                data object About : AppPage
                data class Search(val keyword: String) : AppPage
            }
        """,
        libraries: List<File> = emptyList(),
    ): KspTestResult = runKspSources(mapOf(
        "Destinations.kt" to destinations,
        "Result.kt" to """
            package com.dgtang.live.navigation
            class RouteResult
        """,
        "Registry.kt" to """
            package com.dgtang.live.kuikly.core.ui
            import com.dgtang.live.routing.PageRegistrySpec
            import com.dgtang.live.navigation.AppDestination
            import com.dgtang.live.navigation.RouteResult
            class AppPageOpenContext
            class AppPageGraphContext
            class NavGraphBuilder
            @PageRegistrySpec(AppPageRoute::class, AppPageEntry::class,
                ["Splash", "Login", "LoginPrompt", "Main", "Audience"])
            internal expect object GeneratedAppPageRoutes {
                fun open(host: AppPageOpenContext, destination: AppDestination.AppPage): RouteResult
                fun register(graph: NavGraphBuilder, context: AppPageGraphContext)
            }
        """,
        "PageRuntime.kt" to """
            package com.dgtang.live.kuikly.core.ui
            import com.dgtang.live.navigation.AppDestination
            import kotlin.reflect.KClass
            @Target(AnnotationTarget.PROPERTY)
            annotation class AppPageRoute(val destination: KClass<out AppDestination.AppPage>, val route: String = "")
            class AppPageEntry<T : AppDestination.AppPage>
        """,
        "Entries.kt" to """
            package feature
            import com.dgtang.live.navigation.AppDestination
            import com.dgtang.live.kuikly.core.ui.AppPageRoute
            import com.dgtang.live.kuikly.core.ui.AppPageEntry
            $entries
        """,
    ), listOf(AppPageRouteProcessorProvider()), libraries)
}
