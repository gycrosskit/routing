package com.dgtang.live.routing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/** 请求和结果由消费者定义；每个节点只能放行一次，不另启脱离请求的任务。 */
fun interface RouteInterceptor<Q, R> {
    suspend fun intercept(chain: Chain<Q, R>): R

    interface Chain<Q, R> {
        val request: Q
        suspend fun proceed(request: Q = this.request): R
    }
}

/** 每次调用创建独立链帧；CAS 防并发放行，返回或抛错后关闭本帧，不持锁等待异步工作。 */
suspend fun <Q, R> runRouteInterceptors(
    request: Q,
    interceptors: List<RouteInterceptor<Q, R>>,
    terminal: suspend (Q) -> R,
): R {
    suspend fun proceed(index: Int, request: Q): R {
        currentCoroutineContext().ensureActive()
        if (index == interceptors.size) return terminal(request)
        val state = MutableStateFlow(0)
        val chain = object : RouteInterceptor.Chain<Q, R> {
            override val request = request
            override suspend fun proceed(request: Q): R {
                check(state.compareAndSet(0, 1)) { "路由链已经放行或结束" }
                return proceed(index + 1, request)
            }
        }
        return try { interceptors[index].intercept(chain) }
        finally { state.value = 2 }
    }
    return proceed(0, request)
}
