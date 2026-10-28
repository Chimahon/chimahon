package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class NativeGlossaryGeometryTest {
    @Test fun onlyBlockAxisBordersSeparateVerticalMargins() {
        assertFalse(BoxStyle().separatesVerticalMargins)
        assertFalse(BoxStyle(hasBorder = true, leftAccent = true).separatesVerticalMargins)
        assertTrue(BoxStyle(hasBorder = true).separatesVerticalMargins)
    }

    @Test fun inlineRangesKeepAndroidBoundaryAndEmptyQuerySemantics() {
        assertTrue(inlineRangeOverlaps(2, 5, 3, 4))
        assertFalse(inlineRangeOverlaps(2, 5, 5, 7))
        assertFalse(inlineRangeOverlaps(2, 5, 0, 2))
        assertTrue(inlineRangeOverlaps(2, 5, 2, 2))
        assertTrue(inlineRangeOverlaps(2, 5, 5, 5))
        assertFalse(inlineRangeOverlaps(2, 5, 6, 6))
        assertTrue(inlineRangeOverlaps(3, 3, 2, 4))
    }

    @Test fun orderedInlineRangesFindTheFirstPossibleIntersection() {
        val ends = listOf(3, 8, 12, 17)
        for (offset in 0..20) {
            val expected = ends.indexOfFirst { it >= offset }.let { if (it < 0) ends.size else it }
            assertEquals(expected, firstRangeEndingAtOrAfter(ends, offset) { it })
        }
        assertEquals(0, firstRangeEndingAtOrAfter(emptyList<Int>(), 4) { it })
    }

    @Test fun gridRubyIntrinsicWidthIncludesReadingsWithoutOverhangingOtherRuby() {
        assertEquals(0f, rubyIntrinsicExtra(30f, 20f, 21f))
        assertEquals(33f, rubyIntrinsicExtra(30f, 63f, 21f))
        assertEquals(22.5f, rubyIntrinsicExtra(30f, 63f, 21f, following = 30f))
        assertEquals(12f, rubyIntrinsicExtra(30f, 63f, 21f, preceding = 30f, following = 30f))
        assertEquals(31f, rubyIntrinsicExtra(30f, 63f, 21f, following = 4f))
    }

    @Test fun gridTracksReserveImplicitAutoColumnsBeforeSplittingFractions() {
        val grid = GlossaryTableGrid.place(listOf(List(4) { GlossaryCellSpan() }))
        assertEquals(listOf(0, 120, 240, 360, 420),
            grid.columnEdgesFromFractions(420, floatArrayOf(1f, 1f, 1f), intArrayOf(0, 0, 0, 60)).toList())
        val equal = GlossaryTableGrid.place(listOf(List(3) { GlossaryCellSpan() }))
        assertEquals(listOf(0, 33, 67, 101), equal.columnEdgesFromFractions(101, floatArrayOf(1f, 1f, 1f)).toList())
    }

    @Test fun transparentRowsRetainPositionsInGridButNotInAnonymousTables() {
        val css = parseDictionaryCss("tr { display: contents; }")
        val rows = List(2) { StructuredNode.Element(StructuredTag.Tr,
            List(2) { StructuredNode.Element(StructuredTag.Td, listOf(StructuredNode.Text("x"))) }) }
        val placed = mutableListOf<List<StructuredTableCell>>()
        val run = mutableListOf<StructuredTableCell>()
        for (row in rows) collectTableRowsFlat(row, css, emptyList(), placed, run, gridItems = true)
        assertEquals(listOf(2, 2), placed.map { it.size })
        assertTrue(run.isEmpty())
        placed.clear()
        for (row in rows) collectTableRowsFlat(row, css, emptyList(), placed, run)
        assertTrue(placed.isEmpty())
        assertEquals(4, run.size)
        assertEquals(listOf(0, 1, 0, 1), run.map { it.indexInSourceRow })
    }

    @Test fun standaloneRubyIncludesKanjiBelowItsReadingAndReportsTheBaseBaseline() {
        assertEquals(RubyColumnBox(0f, 0f, 60, 100, 90), rubyColumnBox(30f, 100f, 90f, 0f, 30f, 60, 60f, 30f))
        assertEquals(RubyColumnBox(0f, -4f, 60, 104, 94), rubyColumnBox(30f, 100f, 90f, -3.5f, 26.5f, 60, 60f, 30f))
        assertEquals(RubyColumnBox(0f, 0f, 60, 101, 90), rubyColumnBox(30.25f, 100.25f, 90.25f, 0f, 30f, 60, 60f, 30f))
    }

    @Test fun standaloneRubyContainsReadingsWiderThanTheirKanji() {
        assertEquals(RubyColumnBox(-3f, 0f, 65, 100, 90),
            rubyColumnBox(30f, 100f, 90f, 0f, 30f, 59, 58.8f, 64.68f))
    }

    @Test fun standaloneRubyDropsBlankSpaceBeforeItsLogicalAndInkBounds() {
        assertEquals(RubyColumnBox(-3f, 30f, 65, 95, 85),
            rubyColumnBox(54.29f, 124.85f, 115.21f, 30.2f, 63.21f, 59, 58.8f, 64.68f))
    }

    @Test fun inlineBaselineLengthsAndPercentagesUseTheirOwnElementMetrics() {
        assertEquals(8.4f, inlineBaselineDistance("1em", 8.4f, 11.76f))
        assertEquals(-2.1f, inlineBaselineDistance("-0.25em", 8.4f, 11.76f))
        assertEquals(5f, inlineBaselineDistance("50%", 8.4f, 10f))
        assertEquals(2f, inlineBaselineDistance("2px", 8.4f, 10f))
        assertEquals(null, inlineBaselineDistance("middle", 8.4f, 10f))
        assertEquals(null, inlineBaselineDistance("NaN", 8.4f, 10f))
        assertEquals(null, inlineBaselineDistance("1e999px", 8.4f, 10f))
    }

    @Test fun shortLineBoxesSplitNegativeLeadingWithoutExpandingTheLine() {
        assertEquals(-5, negativeHalfLeading(44, 54))
        assertEquals(-2, negativeHalfLeading(51, 54))
        assertEquals(0, negativeHalfLeading(54, 54))
        assertEquals(0, negativeHalfLeading(60, 54))
    }

    @Test fun fractionalRowsSnapTheirAccumulatedEdgesNotEveryCellHeight() {
        val grid = GlossaryTableGrid.place(List(3) { listOf(GlossaryCellSpan()) })
        assertEquals(listOf(0f, 61.75f, 123.5f, 185.25f), grid.rowEdges(floatArrayOf(61.75f, 61.75f, 61.75f)).toList())
        val spanning = GlossaryTableGrid.place(listOf(
            listOf(GlossaryCellSpan(rows = 2), GlossaryCellSpan()), listOf(GlossaryCellSpan()),
        ))
        assertEquals(listOf(0f, 49.875f, 99.75f), spanning.rowEdges(floatArrayOf(99.75f, 40f, 40f)).toList())
    }

    @Test fun intrinsicWidthsUsePlacedColumnsAfterRowspans() {
        val grid = GlossaryTableGrid.place(listOf(
            listOf(GlossaryCellSpan(rows = 2), GlossaryCellSpan()),
            listOf(GlossaryCellSpan()),
        ))
        assertEquals(listOf(100, 600), grid.columnWidths(intArrayOf(100, 600, 550)).toList())
    }

    @Test fun spanningWidthsGrowOnlyTheirCoveredColumns() {
        val grid = GlossaryTableGrid.place(listOf(
            listOf(GlossaryCellSpan(), GlossaryCellSpan(columns = 2)),
            listOf(GlossaryCellSpan(), GlossaryCellSpan(), GlossaryCellSpan()),
        ))
        assertEquals(listOf(100, 31, 40), grid.columnWidths(intArrayOf(100, 71, 80, 20, 30)).toList())
    }

    @Test fun tableAlignmentRetainsMiddleAndTextBottom() {
        assertEquals("middle", analyzeBoxProps(mapOf("verticalAlign" to "middle"), 14f).verticalAlign)
        assertEquals("text-bottom", analyzeBoxProps(mapOf("verticalAlign" to "text-bottom"), 14f).verticalAlign)
    }

    @Test
    fun columnsUseEveryPixelWithoutIntrinsicMeasurement() {
        val grid = GlossaryTableGrid.place(listOf(List(3) { GlossaryCellSpan() }))
        assertEquals(listOf(0, 33, 67, 101), grid.columnEdges(101, intArrayOf()).toList())
        assertEquals(listOf(0, 0, 0, 1), grid.columnEdges(1, intArrayOf()).toList())
        assertEquals(Int.MAX_VALUE, grid.columnEdges(Int.MAX_VALUE, intArrayOf()).last())
    }

    @Test fun automaticColumnsFitBetweenMinimumAndMaximumContent() {
        val grid = GlossaryTableGrid.place(listOf(List(2) { GlossaryCellSpan() }))
        val minimum = intArrayOf(40, 60)
        val maximum = intArrayOf(100, 400)
        assertEquals(listOf(0, 70, 300), grid.autoColumnEdges(300, minimum, maximum).toList())
        assertEquals(listOf(0, 100, 500), grid.autoColumnEdges(700, minimum, maximum).toList())
        assertEquals(listOf(0, 40, 100), grid.autoColumnEdges(80, minimum, maximum).toList())
        assertEquals(listOf(0, 40, 100), grid.autoColumnEdges(300, minimum, minimum).toList())
        assertEquals(listOf(0, 100, 150), grid.autoColumnEdges(300, intArrayOf(50, 50), intArrayOf(100, 0)).toList())
    }

    @Test fun automaticColumnRoundingPreservesTotalAndMinimumWidths() {
        val grid = GlossaryTableGrid.place(listOf(List(3) { GlossaryCellSpan() }))
        val edges = grid.autoColumnEdges(101, intArrayOf(20, 20, 20), intArrayOf(40, 50, 60))
        assertEquals(101, edges.last())
        assertTrue(edges.toList().zipWithNext().all { (start, end) -> end - start >= 20 })
        assertEquals(listOf(0), GlossaryTableGrid.place(emptyList()).autoColumnEdges(100, intArrayOf(), intArrayOf()).toList())
    }

    @Test
    fun rowSpansReserveTheirColumns() {
        val grid = GlossaryTableGrid.place(listOf(
            listOf(GlossaryCellSpan(rows = 2), GlossaryCellSpan(columns = 2)),
            listOf(GlossaryCellSpan(), GlossaryCellSpan()),
        ))
        assertEquals(3, grid.columns)
        assertEquals(listOf(0, 1, 1, 2), grid.cells.map { it.column })
        assertEquals(listOf(0, 15, 40), grid.rowEdges(intArrayOf(40, 10, 20, 15)).toList())
    }

    @Test
    fun spanningCellsGrowRowsWithoutRelayingOutText() {
        val grid = GlossaryTableGrid.place(listOf(
            listOf(GlossaryCellSpan(rows = 3), GlossaryCellSpan()),
            listOf(GlossaryCellSpan()),
            listOf(GlossaryCellSpan()),
        ))
        val edges = grid.rowEdges(intArrayOf(100, 10, 20, 30))
        assertEquals(100, edges.last())
        assertEquals(listOf(0, 24, 57, 100), edges.toList())
    }

    @Test
    fun malformedSpansAreBoundedAndEmptyRowsStayRepresented() {
        val grid = GlossaryTableGrid.place(listOf(listOf(GlossaryCellSpan(-2, 100)), emptyList(), listOf(GlossaryCellSpan())))
        assertEquals(1, grid.cells.first().columns)
        assertEquals(3, grid.cells.first().rows)
        assertEquals(1, grid.cells.last().column)
        assertEquals(3, grid.rows)
        assertEquals(listOf(0), GlossaryTableGrid.place(emptyList()).columnEdges(100, intArrayOf()).toList())
    }

    @Test
    fun randomizedPlacementDoesNotOverlapOrLoseCellHeight() {
        val random = Random(18102026)
        repeat(500) {
            val rows = List(random.nextInt(1, 16)) {
                List(random.nextInt(0, 9)) { GlossaryCellSpan(random.nextInt(1, 5), random.nextInt(1, 5)) }
            }
            val grid = GlossaryTableGrid.place(rows)
            val occupied = HashSet<Pair<Int, Int>>()
            for (cell in grid.cells) {
                assertTrue(cell.row >= 0 && cell.row + cell.rows <= grid.rows)
                assertTrue(cell.column >= 0 && cell.column + cell.columns <= grid.columns)
                for (row in cell.row until cell.row + cell.rows) for (column in cell.column until cell.column + cell.columns) {
                    assertTrue(occupied.add(row to column), "overlapping table cells")
                }
            }
            val heights = IntArray(grid.cells.size) { random.nextInt(0, 201) }
            val edges = grid.rowEdges(heights)
            for ((index, cell) in grid.cells.withIndex()) assertTrue(edges[cell.row + cell.rows] - edges[cell.row] >= heights[index])
            assertTrue(edges.toList().zipWithNext().all { (a, b) -> b >= a })
            if (grid.columns > 0) {
                val columns = grid.columnEdges(825, intArrayOf())
                assertEquals(825, columns.last())
                assertTrue(columns.toList().zipWithNext().all { (a, b) -> b >= a })
            }
        }
    }
}
