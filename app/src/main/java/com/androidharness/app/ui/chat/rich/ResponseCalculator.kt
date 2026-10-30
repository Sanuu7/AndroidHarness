package com.androidharness.app.ui.chat.rich

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.response.CalculatorMath
import com.androidharness.app.core.response.RichResponse
import kotlin.math.round
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ResponseCalculator(calculator: RichResponse.Node.Calculator, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    var fields by rememberSaveable(calculator) { mutableStateOf(calculator.inputs.map { it.value.toString() }) }
    val values = fields.mapIndexed { index, text -> text.replace(',', '.').toDoubleOrNull()?.takeIf {
        it.isFinite() && it in calculator.inputs[index].min..calculator.inputs[index].max
    } }
    val map = if (values.all { it != null }) calculator.inputs.mapIndexed { index, input -> input.id to requireNotNull(values[index]) }.toMap() else null
    val results = calculator.outputs.map { output -> map?.let { CalculatorMath.evaluate(output.expression, it) } }
    val contextChange = LocalResponseContext.current
    val ui = LocalContext.current
    fun currentContext(): String {
        val current = calculator.inputs.mapIndexed { index, input -> input.id to fields[index].replace(',', '.').toDoubleOrNull() }
        val valid = current.all { (id, value) -> value != null && value.isFinite() && calculator.inputs.first { it.id == id }.let { value in it.min..it.max } }
        return calculator.inputs.mapIndexed { index, input -> "${input.label}: ${fields[index]}" }.joinToString("\n") + "\n" + calculator.outputs.joinToString("\n") { output ->
            val result = if (valid) CalculatorMath.evaluate(output.expression, current.associate { it.first to requireNotNull(it.second) }) else null
            "${output.label}: ${result?.let(CalculatorMath::format) ?: "unavailable"} ${output.unit}"
        }
    }
    ResponsePanel {
        Text(calculator.title, style = MaterialTheme.typography.titleSmall)
        calculator.inputs.forEachIndexed { index, input ->
            OutlinedTextField(fields[index], { text ->
                fields = fields.toMutableList().also { it[index] = text.take(40) }
                contextChange?.invoke(calculator.title, currentContext())
            }, label = { Text(input.label) }, singleLine = true, isError = values[index] == null,
                supportingText = { Text(stringResource(R.string.rich_input_range, CalculatorMath.format(input.min), CalculatorMath.format(input.max))) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Slider(modifier = Modifier.semantics { contentDescription = ui.getString(R.string.rich_adjust_input, input.label) }, value = (((values[index] ?: input.value) - input.min) / (input.max - input.min)).toFloat().coerceIn(0f, 1f),
                onValueChange = { fraction ->
                    val value = (input.min + round((fraction.toDouble() * (input.max - input.min)) / input.step) * input.step).coerceIn(input.min, input.max)
                    fields = fields.toMutableList().also { it[index] = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() }
                    contextChange?.invoke(calculator.title, currentContext())
                })
        }
        HorizontalDivider()
        calculator.outputs.forEachIndexed { index, output ->
            Text(output.label, style = MaterialTheme.typography.labelMedium)
            Text(results[index]?.let { "${CalculatorMath.format(it)} ${output.unit}".trim() } ?: stringResource(R.string.rich_result_unavailable),
                style = MaterialTheme.typography.headlineSmall)
        }
        if (calculator.caption.isNotBlank()) Text(calculator.caption, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { fields = calculator.inputs.map { it.value.toString() }; contextChange?.invoke(calculator.title, currentContext()) }) {
                Text(stringResource(R.string.rich_reset))
            }
            onFollowUp?.let { follow -> TextButton(onClick = {
                follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_values_prompt), calculator.title, currentContext()))
            }) { Text(stringResource(R.string.rich_discuss_values)) } }
        }
    }
}
