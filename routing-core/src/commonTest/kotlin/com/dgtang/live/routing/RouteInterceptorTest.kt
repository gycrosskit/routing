package com.dgtang.live.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class RouteInterceptorTest {
    @Test
    fun genericRequestAndResultTravelInDeclarationOrder() = runTest {
        val calls = mutableListOf<String>()
        val interceptors = listOf("first", "second").map { name ->
            RouteInterceptor<Int, String> { chain ->
                assertEquals(7, chain.request)
                calls += "$name before"
                chain.proceed().also { calls += "$name after" }
            }
        }
        assertEquals("done", runRouteInterceptors(7, interceptors) { calls += "terminal"; "done" })
        assertEquals(listOf("first before", "second before", "terminal", "second after", "first after"), calls)
    }

    @Test
    fun rewrittenRequestFlowsForwardAndResultsUnwindInReverseOrder() = runTest {
        val seen = mutableListOf<Int>()
        val result = runRouteInterceptors(1, listOf(
            RouteInterceptor<Int, String> { chain ->
                seen += chain.request
                "outer(${chain.proceed(2)})"
            },
            RouteInterceptor { chain ->
                seen += chain.request
                "inner(${chain.proceed(chain.request + 1)})"
            },
        )) { value -> seen += value; "done" }
        assertEquals(listOf(1, 2, 3), seen)
        assertEquals("outer(inner(done))", result)
    }

    @Test
    fun shortCircuitDoesNotCallLaterNodes() = runTest {
        assertEquals("denied", runRouteInterceptors(7, listOf(
            RouteInterceptor<Int, String> { "denied" },
            RouteInterceptor { error("后继节点不能执行") },
        )) { error("terminal 不能执行") })
    }

    @Test
    fun repeatedAndReturnedFrameProceedAreRejected() = runTest {
        for (advance in listOf(false, true)) {
            lateinit var frame: RouteInterceptor.Chain<Int, String>
            var dispatches = 0
            val result = runRouteInterceptors(7, listOf(RouteInterceptor<Int, String> { chain ->
                frame = chain
                if (advance) {
                    val first = chain.proceed()
                    assertFailsWith<IllegalStateException> { chain.proceed() }
                    first
                } else "intercepted"
            })) { dispatches++; "done" }
            assertEquals(if (advance) "done" else "intercepted", result)
            assertFailsWith<IllegalStateException> { frame.proceed() }
            assertEquals(if (advance) 1 else 0, dispatches)
        }
    }

    @Test
    fun concurrentProceedUsesOneAtomicDispatch() = runTest {
        var dispatches = 0
        val result = runRouteInterceptors(7, listOf(RouteInterceptor<Int, String> { chain ->
            val results = coroutineScope {
                List(2) { async(Dispatchers.Default) { runCatching { chain.proceed() } } }.awaitAll()
            }
            assertEquals(1, results.count { it.isSuccess })
            assertIs<IllegalStateException>(results.single { it.isFailure }.exceptionOrNull())
            results.single { it.isSuccess }.getOrThrow()
        })) { dispatches++; "done" }
        assertEquals("done", result)
        assertEquals(1, dispatches)
    }

    @Test
    fun cancellationAfterSuspensionPropagatesAndClosesTheFrame() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val cancellation = CancellationException("owner left")
        lateinit var frame: RouteInterceptor.Chain<Int, String>
        val task = async {
            assertFailsWith<CancellationException> {
                runRouteInterceptors(7, listOf(RouteInterceptor<Int, String> { chain ->
                    frame = chain
                    chain.proceed()
                })) {
                    entered.complete(Unit)
                    resume.await()
                    throw cancellation
                }
            }
        }
        entered.await()
        resume.complete(Unit)
        assertSame(cancellation, task.await())
        assertFailsWith<IllegalStateException> { frame.proceed() }
    }
}
