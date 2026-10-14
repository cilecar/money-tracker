package app.moneytracker.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.moneytracker.R
import app.moneytracker.ui.analytics.AnalyticsScreen
import app.moneytracker.ui.budgets.BudgetsScreen
import app.moneytracker.ui.categories.CategoriesScreen
import app.moneytracker.ui.categories.CategoryEditScreen
import app.moneytracker.ui.data.DataScreen
import app.moneytracker.ui.expenses.edit.ExpenseEditScreen
import app.moneytracker.ui.expenses.list.ExpenseListScreen
import app.moneytracker.ui.more.MoreScreen
import app.moneytracker.ui.paymentmethods.PaymentMethodsScreen
import app.moneytracker.ui.rates.RatesScreen
import app.moneytracker.ui.recurring.RecurringEditScreen
import app.moneytracker.ui.recurring.RecurringListScreen
import app.moneytracker.ui.settings.SettingsScreen
import kotlin.reflect.KClass

private data class TopLevel(val route: Any, val routeClass: KClass<*>, @StringRes val label: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(ExpensesRoute, ExpensesRoute::class, R.string.nav_expenses, Icons.AutoMirrored.Filled.ReceiptLong),
    TopLevel(AnalyticsRoute, AnalyticsRoute::class, R.string.nav_analytics, Icons.Filled.PieChart),
    TopLevel(BudgetsRoute, BudgetsRoute::class, R.string.nav_budgets, Icons.Filled.Savings),
    TopLevel(MoreRoute, MoreRoute::class, R.string.nav_more, Icons.Filled.MoreHoriz),
)

@Composable
fun AppRoot(openAdd: Boolean = false, onOpenAddHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    LaunchedEffect(openAdd) {
        if (openAdd) {
            navController.navigate(ExpenseEditRoute())
            onOpenAddHandled()
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = topLevel.any { t -> destination?.hierarchy?.any { it.hasRoute(t.routeClass) } == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevel.forEach { item ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // Стандартный приём для нижней навигации: не копим дубликаты вкладок
                                // в back stack и сохраняем состояние (скролл, фильтры) каждой вкладки.
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets: внутренние Scaffold'ы не добавят отступ под навбар второй раз.
        NavHost(
            navController = navController,
            startDestination = ExpensesRoute,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
        ) {
            composable<ExpensesRoute> {
                ExpenseListScreen(
                    onAdd = { navController.navigate(ExpenseEditRoute()) },
                    onEdit = { navController.navigate(ExpenseEditRoute(it)) },
                )
            }
            composable<ExpenseEditRoute> {
                ExpenseEditScreen(onClose = { navController.popBackStack() })
            }
            composable<AnalyticsRoute> {
                AnalyticsScreen(
                    onCategory = { id, from, to ->
                        navController.navigate(FilteredExpensesRoute(categoryId = id, fromEpochDay = from.toEpochDay(), toEpochDay = to.toEpochDay()))
                    },
                    onPaymentMethod = { id, from, to ->
                        navController.navigate(FilteredExpensesRoute(paymentMethodId = id, fromEpochDay = from.toEpochDay(), toEpochDay = to.toEpochDay()))
                    },
                )
            }
            composable<FilteredExpensesRoute> {
                ExpenseListScreen(
                    onAdd = { navController.navigate(ExpenseEditRoute()) },
                    onEdit = { navController.navigate(ExpenseEditRoute(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<BudgetsRoute> { BudgetsScreen() }
            composable<RecurringListRoute> {
                RecurringListScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(RecurringEditRoute(it)) },
                )
            }
            composable<RecurringEditRoute> { RecurringEditScreen(onClose = { navController.popBackStack() }) }
            composable<MoreRoute> {
                MoreScreen(
                    onCategories = { navController.navigate(CategoriesRoute) },
                    onPaymentMethods = { navController.navigate(PaymentMethodsRoute) },
                    onRecurring = { navController.navigate(RecurringListRoute) },
                    onRates = { navController.navigate(RatesRoute) },
                    onData = { navController.navigate(DataRoute) },
                    onSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<CategoriesRoute> {
                CategoriesScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(CategoryEditRoute(id = it)) },
                    onAdd = { navController.navigate(CategoryEditRoute(parentId = it)) },
                )
            }
            composable<CategoryEditRoute> { CategoryEditScreen(onClose = { navController.popBackStack() }) }
            composable<PaymentMethodsRoute> { PaymentMethodsScreen(onBack = { navController.popBackStack() }) }
            composable<DataRoute> { DataScreen(onBack = { navController.popBackStack() }) }
            composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
            composable<RatesRoute> { RatesScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
