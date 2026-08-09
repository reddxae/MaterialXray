package com.material.xray.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A single-choice segmented control across the width of a screen, used where a pager needs a
 * visible tab strip.
 *
 * Takes resolved labels and an index rather than an enum, so the caller decides what it is
 * selecting between and where the current selection comes from.
 */
@Composable
fun SegmentedTabRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selectedIndex == index,
                onClick = { onSelected(index) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
            ) {
                Text(label)
            }
        }
    }
}
