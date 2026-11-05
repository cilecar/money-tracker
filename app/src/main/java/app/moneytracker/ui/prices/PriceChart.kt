package app.moneytracker.ui.prices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.moneytracker.domain.receipt.PricePoint
import app.moneytracker.ui.analytics.compactNumber
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Линия одной сети: X — день (epochDay), Y — цена за единицу в динарах. */
data class ChartSeries(val chainId: Long, val x: List<Long>, val y: List<BigDecimal>)

/** По сети — точки по дням; несколько покупок в один день — цена последней. */
fun chartSeries(points: List<PricePoint>): List<ChartSeries> = points.groupBy { it.chainId }
    .toSortedMap()
    .map { (chainId, own) ->
        val byDay = own.sortedBy { it.occurredAt }.associateBy { it.date }.toSortedMap()
        ChartSeries(chainId, byDay.keys.map { it.toEpochDay() }, byDay.values.map { BigDecimal.valueOf(it.unitPriceMinor, 2) })
    }

/** Цвета линий по порядку сетей. */
private val SeriesColors = listOf(Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00897B))

private val DayFormatter = DateTimeFormatter.ofPattern("d.MM")

private val XDay = CartesianValueFormatter { _, x, _ -> LocalDate.ofEpochDay(x.toLong()).format(DayFormatter) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceChart(series: List<ChartSeries>, chainNames: Map<Long, String>, modifier: Modifier = Modifier) {
    if (series.isEmpty()) return
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) {
        producer.runTransaction {
            lineSeries { series.forEach { s -> series(x = s.x, y = s.y) } }
        }
    }
    val colors = series.indices.map { SeriesColors[it % SeriesColors.size] }
    Column(modifier) {
        ProvideVicoTheme(rememberM3VicoTheme()) {
            val lines = colors.map { color ->
                // Точки обязательны: одна покупка — это точка, а не линия.
                LineCartesianLayer.rememberLine(
                    fill = remember(color) { LineCartesianLayer.LineFill.single(Fill(color)) },
                    stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp, cap = StrokeCap.Round),
                    pointProvider = LineCartesianLayer.PointProvider.single(
                        LineCartesianLayer.Point(rememberShapeComponent(Fill(color), CircleShape), 8.dp),
                    ),
                )
            }
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider = LineCartesianLayer.LineProvider.series(lines)),
                    startAxis = VerticalAxis.rememberStart(
                        valueFormatter = remember { CartesianValueFormatter { _, value, _ -> compactNumber(value) } },
                        itemPlacer = remember { VerticalAxis.ItemPlacer.count({ 4 }) },
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = XDay, guideline = null),
                ),
                modelProducer = producer,
                modifier = Modifier.height(200.dp),
                scrollState = rememberVicoScrollState(scrollEnabled = true),
            )
        }
        if (series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(10.dp).background(colors[i], CircleShape))
                        Text(chainNames[s.chainId].orEmpty(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
