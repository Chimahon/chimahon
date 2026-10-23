package eu.kanade.tachiyomi.ui.dictionary.compose

/** Cell placement needs neither text measurement nor a min/max-content negotiation. */
public data class GlossaryCellSpan(val columns: Int = 1, val rows: Int = 1)
public data class GlossaryCellPosition(val row: Int, val column: Int, val rows: Int, val columns: Int)
public data class GlossaryTableGrid(val columns: Int, val rows: Int, val cells: List<GlossaryCellPosition>) {
    /** Intrinsic contributions follow placed cells, including columns occupied by a rowspan. */
    fun columnWidths(cellWidths: IntArray): IntArray {
        require(cellWidths.size == cells.size)
        val widths = IntArray(columns)
        for ((index, cell) in cells.withIndex()) if (cell.columns == 1) {
            widths[cell.column] = maxOf(widths[cell.column], cellWidths[index])
        }
        for ((index, cell) in cells.withIndex()) if (cell.columns > 1) {
            val end = cell.column + cell.columns
            val missing = cellWidths[index] - (cell.column until end).sumOf { widths[it] }
            if (missing <= 0) continue
            for (column in 0 until cell.columns) {
                widths[cell.column + column] += missing / cell.columns + if (column < missing % cell.columns) 1 else 0
            }
        }
        return widths
    }

    /** Content-sized edges for anonymous table boxes; ordinary tables use [autoColumnEdges]. */
    fun columnEdges(width: Int, natural: IntArray): IntArray {
        if (natural.isEmpty()) return IntArray(columns + 1) { (width.toLong() * it / columns.coerceAtLeast(1)).toInt() }
        val edges = IntArray(columns + 1)
        var x = 0
        for (column in 0 until columns) {
            edges[column] = x
            x += natural.getOrElse(column) { 0 }.coerceAtLeast(1)
        }
        edges[columns] = x
        return edges
    }

    /** Auto columns interpolate between min/max-content, as in Blink's kMaxGuess distribution. */
    fun autoColumnEdges(available: Int, minimum: IntArray, maximum: IntArray): IntArray {
        require(minimum.size == columns && maximum.size == columns)
        val minTotal = minimum.sumOf { it.toLong() }
        val maxTotal = maximum.indices.sumOf { maxOf(maximum[it], minimum[it]).toLong() }
        val target = available.toLong().coerceIn(minTotal, maxTotal)
        val growth = maxTotal - minTotal
        var minPrefix = 0L
        var growthPrefix = 0L
        return IntArray(columns + 1) { column ->
            if (column > 0) {
                minPrefix += minimum[column - 1]
                growthPrefix += (maximum[column - 1] - minimum[column - 1]).coerceAtLeast(0)
            }
            (minPrefix + if (growth == 0L) 0L else (target - minTotal) * growthPrefix / growth).toInt()
        }
    }

    /**
     * Column edges from a grid template's track sizes, as fractions of the table's width.
     *
     * A table laid out as a CSS grid takes its column widths from the template, not from its
     * content: 小学館's 使い分け table is `width: 100%; display: grid` with equal tracks, so every
     * column is the same width - and a narrow one, which is why the reference wraps each glyph
     * onto its own line and the cell content fits the box.
     */
    fun columnEdgesFromFractions(width: Int, fractions: FloatArray, implicitWidths: IntArray = intArrayOf()): IntArray {
        val columns = this.columns
        if (columns == 0) return IntArray(1) { 0 }
        val total = fractions.sum()
        if (total <= 0f) return IntArray(columns + 1) { (width.toLong() * it / columns.coerceAtLeast(1)).toInt() }
        val implicit = (fractions.size until columns).sumOf { implicitWidths.getOrElse(it) { 0 }.coerceAtLeast(1) }
        val available = (width - implicit).coerceAtLeast(0)
        val edges = IntArray(columns + 1)
        var prefix = 0f
        var x = 0
        for (column in 0 until columns) {
            edges[column] = x
            if (column < fractions.size) {
                prefix += fractions[column]
                x = (available * prefix / total).toInt()
            } else x += implicitWidths.getOrElse(column) { 0 }.coerceAtLeast(1)
        }
        edges[columns] = x
        return edges
    }

    fun rowEdges(cellHeights: IntArray): IntArray {
        val heights = IntArray(rows)
        for ((index, cell) in cells.withIndex()) if (cell.rows == 1) heights[cell.row] = maxOf(heights[cell.row], cellHeights[index])
        for ((index, cell) in cells.withIndex()) if (cell.rows > 1) {
            val missing = (cellHeights[index] - (cell.row until cell.row + cell.rows).sumOf { heights[it] }).coerceAtLeast(0)
            for (r in 0 until cell.rows) heights[cell.row + r] += missing / cell.rows + if (r < missing % cell.rows) 1 else 0
        }
        val tops = IntArray(rows + 1)
        for (row in heights.indices) tops[row + 1] = tops[row] + heights[row]
        return tops
    }

    /** Fractional row-span geometry; the compiler snaps only final paint edges. */
    fun rowEdges(cellHeights: FloatArray): FloatArray {
        val heights = FloatArray(rows)
        for ((index, cell) in cells.withIndex()) if (cell.rows == 1) {
            heights[cell.row] = maxOf(heights[cell.row], cellHeights[index])
        }
        for ((index, cell) in cells.withIndex()) if (cell.rows > 1) {
            var current = 0f
            for (row in cell.row until cell.row + cell.rows) current += heights[row]
            val extra = ((cellHeights[index] - current) / cell.rows).coerceAtLeast(0f)
            for (row in cell.row until cell.row + cell.rows) heights[row] += extra
        }
        var top = 0f
        return FloatArray(rows + 1) { row ->
            if (row > 0) top += heights[row - 1]
            top
        }
    }

    companion object {
        fun place(source: List<List<GlossaryCellSpan>>): GlossaryTableGrid {
            val occupiedUntil = ArrayList<Int>()
            val positions = ArrayList<GlossaryCellPosition>()
            for ((row, cells) in source.withIndex()) {
                var column = 0
                for (cell in cells) {
                    val span = cell.columns.coerceIn(1, 256)
                    while (true) {
                        while (occupiedUntil.size < column + span) occupiedUntil.add(0)
                        val blocked = (column until column + span).firstOrNull { occupiedUntil[it] > row }
                        if (blocked == null) break
                        column = blocked + 1
                    }
                    val rowSpan = cell.rows.coerceIn(1, (source.size - row).coerceAtLeast(1))
                    positions.add(GlossaryCellPosition(row, column, rowSpan, span))
                    for (index in column until column + span) occupiedUntil[index] = row + rowSpan
                    column += span
                }
            }
            return GlossaryTableGrid(occupiedUntil.size, source.size, positions)
        }
    }
}
