package com.dgtang.live.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutePresentationTest {
    @Test
    fun presentationRoundTripsWithoutChangingBusinessPayloadOrUriForm() {
        val presentation = RoutePresentation("入口 标题", false)
        for (uri in listOf("/news/12?_args=%7B%22title%22%3A%22article%22%7D", "brand://page/news/12?id=12")) {
            val encoded = presentation.applyTo(uri)
            assertEquals(presentation, RoutePresentation.parse(encoded))
            assertEquals(uri.startsWith("/"), encoded.startsWith("/"))
            assertEquals(true, encoded.contains(uri.substringBefore('?')))
        }
        assertEquals("/about", RoutePresentation().applyTo("/about"))
        assertEquals(RoutePresentation(), RoutePresentation.parse("/about"))
    }

    @Test
    fun presentationRejectsDuplicateInvalidAndOversizedInput() {
        for (query in listOf("title=a&title=b", "showTitle=true&showTitle=false", "showTitle=1", "title=%0A", "title=" + "a".repeat(201))) {
            assertNull(RoutePresentation.parse("/about?$query"), query)
        }
        assertNull(RoutePresentation.parse("/about?title=a", budget = 5))
    }
}
