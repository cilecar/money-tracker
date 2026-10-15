package app.moneytracker.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.moneytracker.domain.model.CurrencyCode

/** Кнопка в шапке для выбора валюты отображения. */
@Composable
fun CurrencyMenu(current: CurrencyCode?, onSelect: (CurrencyCode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(current?.name.orEmpty()) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CurrencyCode.DISPLAY.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    onClick = {
                        onSelect(c)
                        open = false
                    },
                )
            }
        }
    }
}
