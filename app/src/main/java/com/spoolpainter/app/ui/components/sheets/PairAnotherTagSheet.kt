package com.spoolpainter.app.ui.components.sheets

import androidx.compose.ui.res.stringResource
import com.spoolpainter.app.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairAnotherTagSheet(
    state: PairAnotherTagUiState,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("pair-another-tag-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.isVendorPair) {
                    "标签已关联。是否为此料盘再关联一个标签？"
                } else {
                    "已保存。是否为此料盘再关联一个标签？"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (state.isVendorPair) {
                    "请将标签靠近读卡器，以关联到同一料盘。"
                } else {
                    "将向第二个标签写入相同数据，并保存两个标签的关联。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("pair-another-tag-sheet-done"),
                ) {
                    Text(stringResource(R.string.ui_done))
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.testTag("pair-another-tag-sheet-accept"),
                ) {
                    Text("再关联一个")
                }
            }
        }
    }
}
