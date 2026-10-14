package app.moneytracker.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.moneytracker.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onCategories: () -> Unit,
    onAccounts: () -> Unit,
    onIncomeCategories: () -> Unit,
    onRecurring: () -> Unit,
    onRates: () -> Unit,
    onData: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_more)) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Item(Icons.Default.Category, stringResource(R.string.categories_title), onCategories)
            Item(Icons.Default.CreditCard, stringResource(R.string.accounts_title), onAccounts)
            Item(Icons.Default.Payments, stringResource(R.string.income_categories_title), onIncomeCategories)
            Item(Icons.Default.EventRepeat, stringResource(R.string.recurring_title), onRecurring)
            Item(Icons.Default.CurrencyExchange, stringResource(R.string.rates_title), onRates)
            Item(Icons.Default.ImportExport, stringResource(R.string.data_title), onData)
            Item(Icons.Default.Settings, stringResource(R.string.settings_title), onSettings)
        }
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
    )
}
