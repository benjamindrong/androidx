/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.ui.layout

import androidx.annotation.FloatRange
import androidx.compose.Composable
import androidx.compose.composer
import androidx.compose.state
import androidx.compose.unaryPlus
import androidx.ui.core.Alignment
import androidx.ui.core.ComplexLayout
import androidx.ui.core.Constraints
import androidx.ui.core.Density
import androidx.ui.core.DensityReceiver
import androidx.ui.core.Dp
import androidx.ui.core.IntPx
import androidx.ui.core.IntPxSize
import androidx.ui.core.IntrinsicMeasurable
import androidx.ui.core.ParentData
import androidx.ui.core.Placeable
import androidx.ui.core.constrain
import androidx.ui.core.isFinite
import androidx.ui.core.max
import androidx.ui.core.min
import androidx.ui.core.withDensity

/**
 * Collects information about the children of a [Table] when
 * its body is executed with a [TableChildren] as argument.
 */
class TableChildren internal constructor(private val columnCount: Int) {

    internal val tableChildren = mutableListOf<@Composable() () -> Unit>()
    internal val tableDecorations = mutableListOf<TableDecoration>()

    fun tableRow(children: @Composable() (columnIndex: Int) -> Unit) {
        val rowIndex = tableChildren.size
        tableChildren += {
            ParentData(data = TableChildData(rowIndex)) {
                for (j in 0 until columnCount) {
                    children(j)
                }
            }
        }
    }

    fun addDecoration(decoration: TableDecoration) {
        tableDecorations += decoration
    }
}

typealias TableDecoration =
        @Composable() (verticalOffsets: Array<IntPx>, horizontalOffsets: Array<IntPx>) -> Unit

/**
 * Parent data associated with children to assign a row group.
 */
private data class TableChildData(val rowIndex: Int)

private val IntrinsicMeasurable.rowIndex get() = (parentData as? TableChildData)?.rowIndex

/**
 * Used to specify the size of a [Table]'s column.
 */
abstract class TableColumnWidth private constructor(internal val flexValue: Float) {
    /**
     * Returns the ideal width of the column.
     *
     * Note that the column might be wider than this if it is flexible.
     */
    abstract fun preferredWidth(
        cells: List<TableMeasurable>,
        containerWidth: IntPx,
        density: Density
    ): IntPx

    /**
     * Returns the minimum intrinsic width of the column for the given height.
     *
     * This is used for computing the table's intrinsic dimensions. Defaults to [preferredWidth].
     */
    open fun minIntrinsicWidth(
        cells: List<TableMeasurable>,
        containerWidth: IntPx,
        density: Density,
        availableHeight: IntPx
    ): IntPx {
        return preferredWidth(cells, containerWidth, density)
    }

    /**
     * Returns the minimum intrinsic width of the column for the given height.
     *
     * This is used for computing the table's intrinsic dimensions. Defaults to [preferredWidth].
     */
    open fun maxIntrinsicWidth(
        cells: List<TableMeasurable>,
        containerWidth: IntPx,
        density: Density,
        availableHeight: IntPx
    ): IntPx {
        return preferredWidth(cells, containerWidth, density)
    }

    /**
     * An inflexible column has a fixed size which is computed by [preferredWidth].
     */
    abstract class Inflexible : TableColumnWidth(flexValue = 0f) {
        /**
         * Creates a column width specification which defaults to the width specified by the
         * [preferredWidth] of the receiver, but may also grow by taking a part of the remaining
         * space according to the given [flex] once all the inflexible columns have been measured.
         */
        fun flexible(flex: Float): TableColumnWidth = Flexible(flex, this)
    }

    private data class Flexible(val flex: Float, val other: Inflexible) : TableColumnWidth(flex) {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return other.preferredWidth(cells, containerWidth, density)
        }

        override fun minIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return other.minIntrinsicWidth(cells, containerWidth, density, availableHeight)
        }

        override fun maxIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return other.maxIntrinsicWidth(cells, containerWidth, density, availableHeight)
        }
    }

    /**
     * Sizes the column by taking a part of the remaining space according to [flex] once all the
     * inflexible columns have been measured. Note that this defaults to 0 if no space is available.
     */
    data class Flex(@FloatRange(from = 0.0) private val flex: Float) : TableColumnWidth(flex) {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return IntPx.Zero
        }
    }

    /**
     * Sizes the column to the width of the widest child in that column.
     *
     * Note that in order to compute their preferred widths, the children will be measured with
     * infinite width constraints, which means that some of them may have infinite width. For a
     * wrap content behaviour that avoids this, use [MinIntrinsicWidth] or [MaxIntrinsicWidth].
     */
    object Wrap : Inflexible() {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.preferredWidth())
            }
        }

        override fun minIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.minIntrinsicWidth(availableHeight / cells.size))
            }
        }

        override fun maxIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.maxIntrinsicWidth(availableHeight / cells.size))
            }
        }
    }

    /**
     * Sizes the column to the largest of the minimum intrinsic widths of the children in that
     * column (i.e. the minimum width such that children can layout/paint themselves correctly).
     *
     * Note that this is a very expensive way to size a column. For a wrap content behaviour that
     * skips the intrinsic measurements which compute the column width before measuring, use [Wrap].
     */
    object MinIntrinsic : Inflexible() {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.minIntrinsicWidth(IntPx.Infinity))
            }
        }

        override fun minIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.minIntrinsicWidth(availableHeight / cells.size))
            }
        }

        override fun maxIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.maxIntrinsicWidth(availableHeight / cells.size))
            }
        }
    }

    /**
     * Sizes the column to the largest of the maximum intrinsic widths of the children in that
     * column (i.e. the maximum width such that children can occupy the entire space without waste).
     *
     * Note that this is a very expensive way to size a column. For a wrap content behaviour that
     * skips the intrinsic measurements which compute the column width before measuring, use [Wrap].
     */
    object MaxIntrinsic : Inflexible() {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.maxIntrinsicWidth(IntPx.Infinity))
            }
        }

        override fun minIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.minIntrinsicWidth(availableHeight / cells.size))
            }
        }

        override fun maxIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return cells.fold(IntPx.Zero) { acc, cell ->
                max(acc, cell.maxIntrinsicWidth(availableHeight / cells.size))
            }
        }
    }

    /**
     * Sizes the column to a specific width.
     */
    data class Fixed(private val width: Dp) : Inflexible() {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return withDensity(density) { width.toIntPx() }
        }
    }

    /**
     * Sizes the column to a fraction of the table's maximum width constraint.
     *
     * Note that this defaults to 0 if the maximum width constraints is infinite.
     */
    data class Fraction(
        @FloatRange(from = 0.0, to = 1.0) private val fraction: Float
    ) : Inflexible() {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return if (containerWidth.isFinite()) {
                containerWidth * fraction
            } else {
                IntPx.Zero
            }
        }
    }

    /**
     * Sizes the column to the size that is the minimum of two column width specifications.
     *
     * Both specifications are evaluated, so if either specification is expensive, so is this.
     */
    data class Min(private val a: Inflexible, private val b: Inflexible) : Inflexible() {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return min(
                a.preferredWidth(cells, containerWidth, density),
                b.preferredWidth(cells, containerWidth, density)
            )
        }

        override fun minIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return min(
                a.minIntrinsicWidth(cells, containerWidth, density, availableHeight),
                b.minIntrinsicWidth(cells, containerWidth, density, availableHeight)
            )
        }

        override fun maxIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return min(
                a.maxIntrinsicWidth(cells, containerWidth, density, availableHeight),
                b.maxIntrinsicWidth(cells, containerWidth, density, availableHeight)
            )
        }
    }

    /**
     * Sizes the column to the size that is the maximum of two column width specifications.
     *
     * Both specifications are evaluated, so if either specification is expensive, so is this.
     */
    data class Max(private val a: Inflexible, private val b: Inflexible) : Inflexible() {

        override fun preferredWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density
        ): IntPx {
            return max(
                a.preferredWidth(cells, containerWidth, density),
                b.preferredWidth(cells, containerWidth, density)
            )
        }

        override fun minIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return max(
                a.minIntrinsicWidth(cells, containerWidth, density, availableHeight),
                b.minIntrinsicWidth(cells, containerWidth, density, availableHeight)
            )
        }

        override fun maxIntrinsicWidth(
            cells: List<TableMeasurable>,
            containerWidth: IntPx,
            density: Density,
            availableHeight: IntPx
        ): IntPx {
            return max(
                a.maxIntrinsicWidth(cells, containerWidth, density, availableHeight),
                b.maxIntrinsicWidth(cells, containerWidth, density, availableHeight)
            )
        }
    }
}

/**
 * Collects measurements for the children of a column that
 * are available to implementations of [TableColumnWidth].
 */
data class TableMeasurable internal constructor(
    /**
     * Computes the preferred width of the child by measuring with infinite constraints.
     */
    val preferredWidth: () -> IntPx,

    /**
     * Computes the minimum intrinsic width of the child for the given available height.
     */
    val minIntrinsicWidth: (IntPx) -> IntPx,

    /**
     * Computes the maximum intrinsic width of the child for the given available height.
     */
    val maxIntrinsicWidth: (IntPx) -> IntPx
)

/**
 * Layout model that arranges its children into rows and columns.
 *
 * Example usage:
 *
 * @sample androidx.ui.layout.samples.SimpleTable
 *
 * @sample androidx.ui.layout.samples.TableWithDifferentColumnWidths
 */
@Composable
fun Table(
    columns: Int,
    childAlignment: Alignment = Alignment.TopLeft,
    columnWidth: (columnIndex: Int) -> TableColumnWidth = { TableColumnWidth.Flex(1f) },
    block: TableChildren.() -> Unit
) {
    val verticalOffsets = +state { emptyArray<IntPx>() }
    val horizontalOffsets = +state { emptyArray<IntPx>() }

    val tableChildren: @Composable() () -> Unit = with(TableChildren(columns)) {
        apply(block)
        val composable = @Composable {
            tableChildren.forEach { it() }
            tableDecorations.forEach { decoration ->
                decoration(verticalOffsets.value, horizontalOffsets.value)
            }
        }
        composable
    }

    ComplexLayout(tableChildren) {
        measure { children, constraints ->
            val measurables =
                children.filter { it.rowIndex != null }.groupBy { it.rowIndex }.values.toList()
            val rows = measurables.size
            val placeables = Array(rows) { arrayOfNulls<Placeable>(columns) }

            // Compute column widths and collect flex information.
            var totalFlex = 0f
            var availableSpace =
                if (constraints.maxWidth.isFinite()) constraints.maxWidth else constraints.minWidth
            val columnWidths = Array(columns) { IntPx.Zero }
            for (column in 0 until columns) {
                val spec = columnWidth(column)
                val cells = List(rows) { row ->
                    TableMeasurable(
                        preferredWidth = {
                            placeables[row][column]?.width
                                ?: measurables[row][column].measure(Constraints())
                                    .also { placeables[row][column] = it }.width
                        },
                        minIntrinsicWidth = { measurables[row][column].minIntrinsicWidth(it) },
                        maxIntrinsicWidth = { measurables[row][column].maxIntrinsicWidth(it) }
                    )
                }
                columnWidths[column] = spec.preferredWidth(cells, constraints.maxWidth, density)
                availableSpace -= columnWidths[column]
                totalFlex += spec.flexValue
            }

            // Grow flexible columns to fill available horizontal space.
            if (totalFlex > 0 && availableSpace > IntPx.Zero) {
                for (column in 0 until columns) {
                    val spec = columnWidth(column)
                    if (spec.flexValue > 0) {
                        columnWidths[column] += availableSpace * (spec.flexValue / totalFlex)
                    }
                }
            }

            // Measure the remaining children and calculate row heights.
            val rowHeights = Array(rows) { IntPx.Zero }
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    if (placeables[row][column] == null) {
                        placeables[row][column] = measurables[row][column].measure(
                            Constraints(minWidth = IntPx.Zero, maxWidth = columnWidths[column])
                        )
                    }
                    rowHeights[row] = max(rowHeights[row], placeables[row][column]!!.height)
                }
            }

            // Compute row/column offsets.
            val rowOffsets = Array(rows + 1) { IntPx.Zero }
            val columnOffsets = Array(columns + 1) { IntPx.Zero }
            for (row in 0 until rows) {
                rowOffsets[row + 1] = rowOffsets[row] + rowHeights[row]
            }
            for (column in 0 until columns) {
                columnOffsets[column + 1] = columnOffsets[column] + columnWidths[column]
            }

            // Force recomposition of decorations.
            if (!verticalOffsets.value.contentEquals(rowOffsets)) {
                verticalOffsets.value = rowOffsets
            }
            if (!horizontalOffsets.value.contentEquals(columnOffsets)) {
                horizontalOffsets.value = columnOffsets
            }

            // TODO(calintat): Do something when these do not satisfy constraints.
            val tableSize =
                constraints.constrain(IntPxSize(columnOffsets[columns], rowOffsets[rows]))

            layout(tableSize.width, tableSize.height) {
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val placeable = placeables[row][column]!!
                        val position = childAlignment.align(
                            IntPxSize(
                                width = columnWidths[column] - placeable.width,
                                height = rowHeights[row] - placeable.height
                            )
                        )
                        placeable.place(
                            x = columnOffsets[column] + position.x,
                            y = rowOffsets[row] + position.y
                        )
                    }
                }
            }
        }

        fun DensityReceiver.intrinsicWidth(
            children: List<IntrinsicMeasurable>,
            availableHeight: IntPx,
            minimise: Boolean
        ): IntPx {
            val measurables =
                children.filter { it.rowIndex != null }.groupBy { it.rowIndex }.values.toList()
            val rows = measurables.size

            var totalFlex = 0f
            var flexibleSpace = IntPx.Zero
            var inflexibleSpace = IntPx.Zero

            for (column in 0 until columns) {
                val spec = columnWidth(column)
                val cells = List(rows) { row ->
                    TableMeasurable(
                        preferredWidth = { IntPx.Zero },
                        minIntrinsicWidth = { measurables[row][column].minIntrinsicWidth(it) },
                        maxIntrinsicWidth = { measurables[row][column].maxIntrinsicWidth(it) }
                    )
                }
                val width = if (minimise) {
                    spec.minIntrinsicWidth(cells, IntPx.Infinity, density, availableHeight)
                } else {
                    spec.maxIntrinsicWidth(cells, IntPx.Infinity, density, availableHeight)
                }
                if (spec.flexValue <= 0) {
                    inflexibleSpace += width
                } else {
                    totalFlex += spec.flexValue
                    flexibleSpace = max(flexibleSpace, width / spec.flexValue)
                }
            }
            return flexibleSpace * totalFlex + inflexibleSpace
        }

        fun DensityReceiver.intrinsicHeight(
            children: List<IntrinsicMeasurable>,
            availableWidth: IntPx,
            intrinsicHeight: IntrinsicMeasurable.(IntPx) -> IntPx
        ): IntPx {
            val measurables =
                children.filter { it.rowIndex != null }.groupBy { it.rowIndex }.values.toList()
            val rows = measurables.size

            // Compute column widths and collect flex information.
            var totalFlex = 0f
            var availableSpace = availableWidth
            val columnWidths = Array(columns) { IntPx.Zero }
            for (column in 0 until columns) {
                val spec = columnWidth(column)
                val cells = List(rows) { row ->
                    TableMeasurable(
                        preferredWidth = { IntPx.Zero },
                        minIntrinsicWidth = { measurables[row][column].minIntrinsicWidth(it) },
                        maxIntrinsicWidth = { measurables[row][column].maxIntrinsicWidth(it) }
                    )
                }
                columnWidths[column] =
                    spec.maxIntrinsicWidth(cells, availableWidth, density, IntPx.Infinity)
                availableSpace -= columnWidths[column]
                totalFlex += spec.flexValue
            }

            // Grow flexible columns to fill available horizontal space.
            if (totalFlex > 0 && availableSpace > IntPx.Zero) {
                for (column in 0 until columns) {
                    val spec = columnWidth(column)
                    if (spec.flexValue > 0) {
                        columnWidths[column] += availableSpace * (spec.flexValue / totalFlex)
                    }
                }
            }

            // Calculate row heights and table height.
            return (0 until rows).fold(IntPx.Zero) { tableHeight, row ->
                val rowHeight = (0 until columns).fold(IntPx.Zero) { rowHeight, column ->
                    max(rowHeight, measurables[row][column].intrinsicHeight(columnWidths[column]))
                }
                tableHeight + rowHeight
            }
        }

        minIntrinsicWidth { measurables, availableHeight ->
            intrinsicWidth(
                children = measurables,
                availableHeight = availableHeight,
                minimise = true
            )
        }

        minIntrinsicHeight { measurables, availableWidth ->
            intrinsicHeight(
                children = measurables,
                availableWidth = availableWidth,
                intrinsicHeight = { w -> minIntrinsicHeight(w) }
            )
        }

        maxIntrinsicWidth { measurables, availableHeight ->
            intrinsicWidth(
                children = measurables,
                availableHeight = availableHeight,
                minimise = false
            )
        }

        maxIntrinsicHeight { measurables, availableWidth ->
            intrinsicHeight(
                children = measurables,
                availableWidth = availableWidth,
                intrinsicHeight = { w -> maxIntrinsicHeight(w) }
            )
        }
    }
}
