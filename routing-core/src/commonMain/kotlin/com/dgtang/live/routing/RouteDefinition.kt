package com.dgtang.live.routing

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject

/** PUBLIC 可从 URI 进入，RENDERER 只开放可信信封，INTERNAL 仅供宿主版本协议解码。 */
enum class RouteExposure { INTERNAL, RENDERER, PUBLIC }

/** 元数据来自可信声明；具体目标及业务准入由消费者扩展，不从 URI 读取。 */
open class RouteDefinition<T>(
    val name: String,
    val path: String,
    val exposure: RouteExposure,
    val publicParameters: Set<String>,
    val positiveParameters: Set<String>,
    val descriptor: SerialDescriptor,
    val decode: (JsonObject) -> T,
    val encode: (T) -> JsonObject?,
)
