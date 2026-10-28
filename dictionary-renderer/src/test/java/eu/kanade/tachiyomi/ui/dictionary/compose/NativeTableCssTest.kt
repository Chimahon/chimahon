package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.random.Random

class NativeTableCssTest {
    private val css = parseDictionaryCss("""
        * { margin: 9px; padding: 3px; }
        tr { color: red; font-weight: normal; border: 7px solid green; }
        [data-sc-row="accent"] { color: blue; text-align: right; font-size: 1.2em; }
        td { padding: 2px; border: 1px solid black; }
        th { font-weight: bold; }
        [data-sc-cell] { padding-left: 5px; background-color: yellow; }
        table[data-sc-kind="example"] tr td { margin-top: 4px; color: green; }
        tr:nth-child(2) td { border-color: blue; }
        td:first-child { padding-top: 6px; }
        td:last-child { padding-bottom: 7px; }
        th:nth-child(2) { font-style: italic; }
        td:has(> ruby) { color: purple; }
        td:last-child:has(span) { text-align: center; }
        tr[data-sc-splice] { display: contents; }
        table[data-sc-kind="example"] tr { display: contents; }
    """.trimIndent())

    @Test fun nativeCellsMatchReferenceCascadeAndBoxOrderAcrossPositionsAndRows() {
        val random = Random(5532)
        val table = CssAncestor(dataValueKeys(mapOf("kind" to "example")), "table")
        val context = NativeCssContext(css)
        context.push(table.values, table.tag)
        repeat(20) { rowIndex ->
            val frame = CssAncestor(dataValueKeys(mapOf("row" to "accent")), "tr", rowIndex + 1, rowIndex == 19)
            context.push(frame.values, frame.tag, frame.siblingIndex, frame.isLastSibling)
            repeat(8) { cellIndex ->
                // A spliced anonymous row may contain a different source row's inheritance.
                val rowData = if (random.nextBoolean()) mapOf("row" to "accent") else emptyMap()
                val element = StructuredNode.Element(if (random.nextBoolean()) StructuredTag.Th else StructuredTag.Td,
                    children = listOf(StructuredNode.Element(if (random.nextBoolean()) StructuredTag.Ruby else StructuredTag.Span)),
                    attributes = StructuredAttributes(data = if (random.nextBoolean()) mapOf("cell" to "") else emptyMap(),
                        style = if (random.nextBoolean()) mapOf("borderWidth" to "2px", "color" to "orange") else emptyMap()))
                val cell = StructuredTableCell(element.children, element.tag == StructuredTag.Th, element, rowData,
                    indexInSourceRow = cellIndex, sourceRowSize = 8)
                val inherited = context.tableRowInheritance(rowData)
                assertSame(inherited, context.tableRowInheritance(rowData.toMap()))
                val expected = tableCellCss(cell, css, listOf(table, frame))
                val actual = context.tableCellDeclarations(cell, inherited)
                assertEquals(expected, actual)
                assertEquals(parseBoxStyle(expected, 16f), parseBoxStyle(actual, 16f))
            }
            context.pop()
        }
        context.pop()
    }

    @Test fun rowDisplayUsesTheReferenceFlatNestedInlineOrder() {
        val table = CssAncestor(dataValueKeys(mapOf("kind" to "example")), "table")
        val context = NativeCssContext(css)
        context.push(table.values, table.tag)
        for (style in listOf(emptyMap(), mapOf("display" to "table-row"))) {
            val row = StructuredNode.Element(StructuredTag.Tr, attributes = StructuredAttributes(
                data = mapOf("splice" to ""), style = style))
            val expected = getCssStyles(row.attributes.data, css, "tr") +
                getNestedStyles("tr", row.attributes.data, listOf(table), css) + style
            assertEquals(expected, context.tableRowDeclarations(row))
        }
    }

    @Test fun inheritedRowsNeverTransferBoxDeclarationsToCells() {
        val context = NativeCssContext(css)
        val cell = StructuredTableCell(emptyList(), false)
        val actual = context.tableCellDeclarations(cell, context.tableRowInheritance(mapOf("row" to "accent")))
        assertEquals("blue", actual["color"])
        assertEquals("right", actual["textAlign"])
        assertEquals("1px solid black", actual["border"])
        assertEquals(tableCellCss(cell.copy(rowData = mapOf("row" to "accent")), css, emptyList()), actual)
    }
}
