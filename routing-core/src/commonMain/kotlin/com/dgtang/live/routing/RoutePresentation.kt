package com.dgtang.live.routing

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.serialization.Serializable

/** 单个页面条目的展示参数；是否允许覆盖及如何呈现由宿主决定。 */
@Serializable
data class RoutePresentation(val title: String? = null, val showTitle: Boolean? = null) {
    /** 保留业务参数，只写入独立的展示 Query；支持内部相对路径和完整公开 URI。 */
    fun applyTo(uri: String): String {
        if (this == RoutePresentation()) return uri
        val relative = uri.startsWith("/")
        val url = URLBuilder(if (relative) "https://route.invalid$uri" else uri).apply {
            title?.let { parameters["title"] = it }
            showTitle?.let { parameters["showTitle"] = it.toString() }
        }.build()
        return if (relative) url.encodedPath + if (url.encodedQuery.isEmpty()) "" else "?${url.encodedQuery}" else url.toString()
    }

    companion object {
        /** 无覆盖返回默认值；重复、非法值或超出入口预算返回 null，不替代宿主的来源校验。 */
        fun parse(uri: String, budget: Int = MAX_ROUTE_URI_BYTES): RoutePresentation? = runCatching {
            require(uri.encodeToByteArray().size <= budget)
            val url = Url(if (uri.startsWith("/")) "https://route.invalid$uri" else uri)
            fun single(name: String): String? {
                val values = url.parameters.getAll(name) ?: return null
                require(values.size == 1)
                return values.single()
            }
            val title = single("title")?.also { value ->
                require(value.length <= 200 && value.none { it.code < 32 || it.code == 127 })
            }
            val showTitle = single("showTitle")?.let { value ->
                when (value) { "true" -> true; "false" -> false; else -> error("showTitle 必须是布尔值") }
            }
            RoutePresentation(title, showTitle)
        }.getOrNull()
    }
}
