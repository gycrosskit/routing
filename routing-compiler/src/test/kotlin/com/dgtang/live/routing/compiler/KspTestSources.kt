package com.dgtang.live.routing.compiler

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSNode
import com.dgtang.live.routing.RouteExposure
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal data class KspTestResult(val exit: String, val errors: List<String>, val outputs: Map<String, String>)

internal fun kspTestLogger(errors: MutableList<String>): KSPLogger = object : KSPLogger {
    override fun error(message: String, symbol: KSNode?) { errors += message }
    override fun exception(e: Throwable) { throw e }
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) = Unit
}

internal fun kspTestConfig(
    root: File,
    sources: List<File>,
    classpath: List<File>,
    module: String,
    projectDir: File = root,
): KSPJvmConfig = KSPJvmConfig.Builder().apply {
    moduleName = module
    sourceRoots = sources
    libraries = classpath + File(RouteExposure::class.java.protectionDomain.codeSource.location.toURI())
    projectBaseDir = projectDir
    outputBaseDir = root.resolve("out")
    cachesDir = root.resolve("caches")
    classOutputDir = root.resolve("out/classes")
    kotlinOutputDir = root.resolve("out/kotlin")
    resourceOutputDir = root.resolve("out/resources")
    javaOutputDir = root.resolve("out/java")
    languageVersion = "2.2"
    apiVersion = "2.2"
    jdkHome = File(System.getProperty("java.home"))
    jvmTarget = "21"
}.build()

internal fun runKspSources(
    sources: Map<String, String>,
    processors: List<SymbolProcessorProvider>,
    libraries: List<File> = emptyList(),
    consumer: String? = null,
): KspTestResult {
    val workspace = File("build/ksp-test-workspaces").apply { mkdirs() }
    val root = Files.createTempDirectory(workspace.toPath(), "fixture-").toFile().absoluteFile
    try {
        val source = root.resolve("src").apply { mkdirs() }
        sources.forEach { (name, contents) ->
            source.resolve(name).apply { parentFile.mkdirs(); writeText(contents.trimIndent()) }
        }
        val errors = mutableListOf<String>()
        val logger = kspTestLogger(errors)
        val config = kspTestConfig(root, listOf(source), libraries +
            File(Unit::class.java.protectionDomain.codeSource.location.toURI()), "route-test")
        val exit = KotlinSymbolProcessing(config, processors, logger).execute()
        val output = config.kotlinOutputDir
        if (consumer != null && exit.toString() == "OK" && errors.isEmpty()) {
            compileAndRunConsumer(root, source, output, consumer)
        }
        return KspTestResult(exit.toString(), errors, output.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(output).invariantSeparatorsPath to it.readText() })
    } finally {
        root.deleteRecursively()
    }
}

/** 编译完整 expect/actual 与生成表，再执行第二宿主自己的使用断言。 */
private fun compileAndRunConsumer(root: File, source: File, generated: File, consumer: String) {
    val common = source.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    val actual = generated.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    val classes = root.resolve("consumer-classes")
    val log = root.resolve("consumer-compile.log")
    val command = listOf(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-cp", checkNotNull(System.getProperty("routingCompilerClasspath")),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-Xmulti-platform",
        "-Xexpect-actual-classes", "-Xcommon-sources=${common.joinToString(",") { it.absolutePath }}",
        "-classpath", checkNotNull(System.getProperty("routingConsumerClasspath")),
        "-d", classes.absolutePath,
    ) + (common + actual).map { it.absolutePath }
    val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor()
        error("第二宿主消费编译超时：\n${log.readText()}")
    }
    check(process.exitValue() == 0) { "第二宿主消费编译失败：\n${log.readText()}" }
    URLClassLoader(arrayOf(classes.toURI().toURL()), KspTestResult::class.java.classLoader).use { loader ->
        loader.loadClass(consumer).getMethod("verifyConsumer").invoke(null)
    }
}
