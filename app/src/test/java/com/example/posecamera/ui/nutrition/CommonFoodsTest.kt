package com.example.posecamera.ui.nutrition

import com.example.posecamera.data.CatalogFood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonFoodsTest {
    private fun food(slug: String) = CatalogFood(slug, slug.uppercase(), null, slug, null, null, 100.0, 1.0, 1.0, 1.0, true, "{}")

    @Test fun resolvesInCuratedOrderWithCuratedLabel() {
        val r = resolveCommon(listOf(food("b"), food("a")), listOf(CommonFood("Alpha", "a"), CommonFood("Beta", "b")))
        assertEquals(listOf("Alpha", "Beta"), r.map { it.first.label })
        assertEquals(listOf("a", "b"), r.map { it.second.slug })
    }

    @Test fun skipsSlugsMissingFromCatalog() {
        val r = resolveCommon(listOf(food("b")), listOf(CommonFood("Alpha", "a"), CommonFood("Beta", "b")))
        assertEquals(listOf("Beta"), r.map { it.first.label })
        assertTrue(resolveCommon(emptyList()).isEmpty())
    }

    @Test fun curatedListHas25DistinctSlugs() {
        assertEquals(25, COMMON_FOODS.size)
        assertEquals(25, COMMON_FOODS.map { it.slug }.toSet().size)
        assertTrue(COMMON_FOODS.any { it.slug == "ifct-a011-rice-flakes" })
    }

    @Test fun headerThresholdIsFour() {
        assertEquals(4, MIN_COMMON)
    }
}
