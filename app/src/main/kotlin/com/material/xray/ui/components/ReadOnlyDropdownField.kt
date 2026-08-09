package com.material.xray.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A text field that opens a menu instead of a keyboard, for picking one of a fixed set of values.
 *
 * The field never holds its own text. [selectedText] is what it displays, which is deliberately
 * independent of [options]: a stored value can fall outside the set currently offered, and the
 * field still has to render it. The menu's open state is internal, so a caller that needs it to
 * close when the subject changes should wrap this in a `key`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ReadOnlyDropdownField(
    label: String,
    selectedText: String,
    options: List<DropdownOption<T>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        if (option.description == null) {
                            Text(option.label)
                        } else {
                            Column {
                                Text(option.label)
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    },
                )
            }
        }
    }
}

/**
 * One choice offered by a [ReadOnlyDropdownField].
 *
 * @property value what the caller gets back when this choice is picked.
 * @property label the single line shown in the menu.
 * @property description an optional second line explaining the choice.
 */
data class DropdownOption<out T>(
    val value: T,
    val label: String,
    val description: String? = null,
)
