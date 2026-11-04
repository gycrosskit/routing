package com.dgtang.live.routing

import kotlin.reflect.KClass

/** 宿主声明类型和序列化入口；生成器不持有业务目的地、准入枚举或包名。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RouteRegistrySpec(
    val annotation: KClass<out Annotation>,
    val destinationRoot: KClass<*>,
    val target: KClass<*>,
    val wrapper: KClass<*>,
    val json: String,
)

/** 页面开页与装配签名取自注册表契约，根页面保留名由宿主决定。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class PageRegistrySpec(
    val annotation: KClass<out Annotation>,
    val entry: KClass<*>,
    val reservedRoutes: Array<String> = [],
)
