package com.example.posecamera.ui.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodLabelTest {
    @Test fun nameWins() {
        assertEquals("Bread", foodLabel("Bread, chapati or roti", "ifct-a011-rice-flakes"))
    }

    @Test fun blankNameFallsToSlug() {
        assertEquals("Poha", foodLabel("  ", "ifct-a011-rice-flakes"))
    }

    @Test fun everyCommonSlugIsClean() {
        for (c in COMMON_FOODS) {
            val l = foodLabel(null, c.slug)
            assertTrue(c.slug, l.isNotEmpty())
            assertFalse(c.slug, l.startsWith("Ifct", ignoreCase = true) || l.startsWith("Usda", ignoreCase = true))
            assertFalse(c.slug, Regex("""\b[a-z]\d{3}\b""", RegexOption.IGNORE_CASE).containsMatchIn(l))
        }
    }

    @Test fun knownExamples() {
        assertEquals("Poha", foodLabel(null, "ifct-a011-rice-flakes"))
        assertEquals("Milk", foodLabel(null, "ifct-l002-milk-whole-cow"))
        assertEquals("Moong dal", foodLabel(null, "ifct-b010-green-gram-dal"))
    }

    @Test fun unknownSlugsAreCleaned() {
        assertEquals("Some food", foodLabel(null, "ifct-x999-some-food"))
        assertEquals("Bread naan plain", foodLabel(null, "usda-sr-999999-bread-naan-plain"))
    }

    @Test fun nothingGivesFood() {
        assertEquals("Food", foodLabel(null, null))
        assertEquals("Food", foodLabel(null, ""))
    }
}
