package app.moneytracker.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.PaymentSlice
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatMoney
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Значения для графиков — Double: это координаты пикселей, а не деньги.
 * Все суммы, которые видит пользователь текстом, по-прежнему форматируются из Money.
 */
private fun Money.chartValue(): Double = amount.toDouble()

/** Подписи оси Y: 950, 1,2k, 15k, 1,5M. */
internal fun compactNumber(value: Double): String = when {
    value >= 1_000_000 -> trim(value / 1_000_000) + "M"
    value >= 1_000 -> trim(value / 1_000) + "k"
    else -> value.toLong().toString()
}

/** Доля в процентах с округлением, а не отбрасыванием: 10,7% — «11%». Ненулевая крошка — «<1%», а не «0%». */
fun formatShare(share: Float): String = when {
    share > 0f && share < 0.005f -> "<1%"
    else -> "${(share * 100).roundToInt()}%"
}

private fun trim(v: Double): String = if (v < 10) "%.1f".format(v).trimEnd('0').trimEnd('.', ',') else v.toLong().toString()

/** 1. Расходы по дням/месяцам — столбцы. */
@Composable
fun SpendingColumnChart(values: List<Money>, labels: List<String>, labelSpacing: Int, modifier: Modifier = Modifier) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction { columnSeries { series(values.map { it.chartValue() }) } }
    }
    val color = MaterialTheme.colorScheme.primary
    val maxY = values.maxOfOrNull { it.chartValue() } ?: 0.0
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(Fill(color), thickness = 10.dp, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    ),
                    // Все нули — фиксируем ось, иначе диапазон 0..0 вырождается.
                    rangeProvider = remember(maxY) { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = if (maxY <= 0.0) 1.0 else null) },
                ),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = remember { CartesianValueFormatter { _, value, _ -> compactNumber(value) } },
                    itemPlacer = remember { VerticalAxis.ItemPlacer.count({ 4 }) },
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = remember(labels) { CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } } },
                    itemPlacer = remember(labelSpacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { labelSpacing }) },
                    guideline = null,
                ),
            ),
            modelProducer = producer,
            modifier = modifier.height(200.dp),
            // Весь период на одном экране, без прокрутки: 31 столбец ужимается под ширину.
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

/** 4. Сравнение: накопительные суммы текущего (сплошная) и прошлого (пунктир) периодов. */
@Composable
fun ComparisonLineChart(current: List<Money>, previous: List<Money>, labels: List<String>, labelSpacing: Int, modifier: Modifier = Modifier) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(current, previous) {
        // Vico не принимает пустую серию — подставляем одну точку 0.
        val prev = previous.map { it.chartValue() }.ifEmpty { listOf(0.0) }
        val cur = current.map { it.chartValue() }.ifEmpty { listOf(0.0) }
        producer.runTransaction {
            lineSeries {
                series(prev)
                series(cur)
            }
        }
    }
    val currentColor = MaterialTheme.colorScheme.primary
    val previousColor = MaterialTheme.colorScheme.outline
    val maxY = (current + previous).maxOfOrNull { it.chartValue() } ?: 0.0
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            fill = remember(previousColor) { LineCartesianLayer.LineFill.single(Fill(previousColor)) },
                            stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 2.dp),
                        ),
                        LineCartesianLayer.rememberLine(
                            fill = remember(currentColor) { LineCartesianLayer.LineFill.single(Fill(currentColor)) },
                            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 3.dp, cap = StrokeCap.Round),
                        ),
                    ),
                    rangeProvider = remember(maxY) { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = if (maxY <= 0.0) 1.0 else null) },
                ),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = remember { CartesianValueFormatter { _, value, _ -> compactNumber(value) } },
                    itemPlacer = remember { VerticalAxis.ItemPlacer.count({ 4 }) },
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = remember(labels) { CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } } },
                    itemPlacer = remember(labelSpacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { labelSpacing }) },
                    guideline = null,
                ),
            ),
            modelProducer = producer,
            modifier = modifier.height(180.dp),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

data class DonutSegment(val key: Any?, val share: Float, val color: Color)

/**
 * 2. Donut на Canvas. В Vico 3 есть PieChart, но без обработки нажатий на сектор,
 * а здесь тап выделяет категорию. Своя реализация — ~60 строк и полный контроль.
 */
@Composable
fun DonutChart(
    segments: List<DonutSegment>,
    selectedKey: Any?,
    onSelect: (Any?) -> Unit,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    val gapDegrees = if (segments.size > 1) 1.5f else 0f
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(segments) {
                    detectTapGestures { tap ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val radius = min(size.width, size.height) / 2f
                        val distance = hypot(tap.x - c.x, tap.y - c.y)
                        // Тап только по кольцу: внутри «дырки» — снять выделение.
                        if (distance < radius * 0.55f || distance > radius) {
                            onSelect(null)
                            return@detectTapGestures
                        }
                        // Угол от 12 часов по часовой стрелке, 0..360.
                        val angle = (Math.toDegrees(atan2((tap.y - c.y).toDouble(), (tap.x - c.x).toDouble())) + 90 + 360) % 360
                        var start = 0.0
                        for (s in segments) {
                            val sweep = s.share * 360.0
                            if (angle < start + sweep) {
                                onSelect(if (s.key == selectedKey) null else s.key)
                                return@detectTapGestures
                            }
                            start += sweep
                        }
                    }
                },
        ) {
            val stroke = size.minDimension * 0.18f
            val selectedStroke = stroke * 1.25f
            val inset = selectedStroke / 2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            var start = -90f
            segments.forEach { s ->
                val sweep = s.share * 360f
                val selected = s.key == selectedKey
                drawArc(
                    color = if (selectedKey == null || selected) s.color else s.color.copy(alpha = 0.35f),
                    startAngle = start + gapDegrees / 2,
                    sweepAngle = (sweep - gapDegrees).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = if (selected) selectedStroke else stroke),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) { center() }
    }
}

/** 3. Способы оплаты — горизонтальные полосы: при 2–5 элементах читается лучше круговой. */
@Composable
fun PaymentBars(slices: List<PaymentSlice>, onClick: (PaymentSlice) -> Unit) {
    val locale = currentLocale()
    val max = slices.maxOfOrNull { it.share } ?: 1f
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        slices.forEach { s ->
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick(s) }.padding(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.method?.let { Icon(CategoryIcons.forPaymentType(it.type), null, Modifier.size(18.dp)) }
                    Text(
                        s.method?.displayName() ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatMoney(s.amount, locale), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatShare(s.share),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (max > 0f) s.share / max else 0f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
        }
    }
}
