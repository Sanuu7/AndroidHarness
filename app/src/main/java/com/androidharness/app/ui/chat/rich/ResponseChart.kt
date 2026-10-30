package com.androidharness.app.ui.chat.rich

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.ui.common.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResponseChart(chart: RichResponse.Node.Chart, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    val ui = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val series = remember(chart) { chart.series.ifEmpty { listOf(RichResponse.Series(chart.title, chart.points)) } }
    val palette = listOf(colors.primary, colors.tertiary, colors.secondary, colors.error, colors.onSurface, colors.outline)
    var visible by rememberSaveable(chart) { mutableStateOf(List(series.size) { true }) }
    var selected by rememberSaveable(chart) { mutableIntStateOf(chart.points.lastIndex) }
    var start by rememberSaveable(chart) { mutableIntStateOf(0) }
    var end by rememberSaveable(chart) { mutableIntStateOf(chart.points.lastIndex) }
    var data by rememberSaveable(chart) { mutableStateOf(false) }
    val contextChange = LocalResponseContext.current
    val active = series.indices.filter { visible[it] }
    val current = selected.coerceIn(start, end)
    val values = active.flatMap { series[it].points.subList(start, end + 1).map { p -> p.value } }
    val low = minOf(0.0, values.min()); val high = maxOf(0.0, values.max())
    val range = (high - low).takeIf { it > 0 } ?: 1.0
    fun context(): String = "Range: ${chart.points[start].label} to ${chart.points[end].label}\nSelected: ${chart.points[selected.coerceIn(start, end)].label}\n" +
        series.indices.filter { visible[it] }.joinToString("\n") { "${series[it].name}: ${RichResponse.number(series[it].points[selected.coerceIn(start, end)].value)} ${chart.unit}" }
    ResponsePanel {
        Text(chart.title, style = MaterialTheme.typography.titleSmall)
        Text(chart.points[current].label, style = MaterialTheme.typography.bodyMedium)
        active.forEach { index -> Text("${series[index].name}: ${RichResponse.number(series[index].points[current].value)} ${chart.unit}".trim(),
            style = MaterialTheme.typography.labelMedium, color = palette[index]) }
        if (series.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            series.forEachIndexed { index, item -> FilterChip(visible[index], onClick = {
                if (!visible[index] || visible.count { it } > 1) {
                    visible = visible.toMutableList().also { it[index] = !it[index] }
                    contextChange?.invoke(chart.title, context())
                }
            }, label = { Text(item.name, color = palette[index]) }) }
        }
        Canvas(Modifier.fillMaxWidth().height(170.dp).semantics {
            contentDescription = ui.getString(R.string.ui_values_use_show_data_for_all_values, chart.title, end - start + 1)
        }.pointerInput(chart, start, end, visible) { detectTapGestures { tap ->
            selected = (start + ((tap.x / size.width.coerceAtLeast(1)) * (end - start + 1)).toInt()).coerceIn(start, end)
            contextChange?.invoke(chart.title, context())
        } }) {
            val count = end - start + 1; val cell = size.width / count
            fun y(value: Double) = (size.height - 12.dp.toPx()) * (1 - ((value - low) / range).toFloat()) + 6.dp.toPx()
            val zero = y(0.0)
            drawLine(colors.outlineVariant, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
            active.forEachIndexed { group, index ->
                val points = series[index].points.subList(start, end + 1)
                val color = palette[index]
                if (chart.kind == "line") {
                    val path = Path()
                    points.forEachIndexed { i, p -> val x = cell * (i + .5f); if (i == 0) path.moveTo(x, y(p.value)) else path.lineTo(x, y(p.value)) }
                    drawPath(path, color, style = Stroke(2.dp.toPx()))
                    points.forEachIndexed { i, p -> drawCircle(color, if (start + i == current) 5.dp.toPx() else 3.dp.toPx(), Offset(cell * (i + .5f), y(p.value))) }
                } else points.forEachIndexed { i, p ->
                    val py = y(p.value); val width = cell * .76f / active.size
                    drawRoundRect(if (start + i == current) color else color.copy(alpha = .5f), Offset(cell * i + cell * .12f + group * width, minOf(py, zero)),
                        Size(width * .9f, kotlin.math.abs(py - zero).coerceAtLeast(1.dp.toPx())), CornerRadius(2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(chart.points[start].label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            if (end > start) Text(chart.points[end].label, style = MaterialTheme.typography.labelSmall)
        }
        if (chart.points.size > 2) {
            Text(stringResource(R.string.rich_chart_range), style = MaterialTheme.typography.labelMedium)
            RangeSlider(value = start.toFloat()..end.toFloat(), onValueChange = { window ->
                start = window.start.toInt().coerceIn(0, chart.points.lastIndex - 1)
                end = window.endInclusive.toInt().coerceIn(start + 1, chart.points.lastIndex)
                contextChange?.invoke(chart.title, context())
            }, valueRange = 0f..chart.points.lastIndex.toFloat(), steps = chart.points.size - 2)
            if (start != 0 || end != chart.points.lastIndex) TextButton(onClick = { start = 0; end = chart.points.lastIndex; contextChange?.invoke(chart.title, context()) }) {
                Text(stringResource(R.string.rich_reset_range))
            }
        }
        if (chart.caption.isNotBlank()) Text(chart.caption, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { data = !data }) { Text(stringResource(if (data) R.string.ui_hide_data else R.string.ui_show_data)) }
        if (data) Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row { Text(stringResource(R.string.rich_chart_label), Modifier.width(120.dp)); active.forEach { Text(series[it].name, Modifier.width(120.dp)) } }
            for (index in start..end) Row {
                TextButton(onClick = { selected = index; contextChange?.invoke(chart.title, context()) }, Modifier.width(120.dp).semantics { contentDescription = ui.getString(R.string.rich_select_point, chart.points[index].label) }) { Text(chart.points[index].label) }
                active.forEach { Text("${RichResponse.number(series[it].points[index].value)} ${chart.unit}", Modifier.width(120.dp).padding(vertical = 12.dp)) }
            }
        }
        onFollowUp?.let { follow -> TextButton(onClick = { follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_chart_prompt), chart.title, context())) }) {
            Text(stringResource(R.string.rich_discuss_chart))
        } }
    }
}
