package app.moneytracker.ui.navigation

import app.moneytracker.ui.prices.ProductScreen
import app.moneytracker.ui.prices.PricesScreen
import androidx.compose.material.icons.filled.QrCodeScanner
import app.moneytracker.ui.capture.AutoCaptureScreen
import app.moneytracker.ui.capture.CaptureDebugScreen
import app.moneytracker.ui.capture.PendingCapturesScreen
import androidx.navigation.NavHostController
import app.moneytracker.ui.incomecategories.IncomeCategoriesScreen
import app.moneytracker.ui.operations.IncomeEditScreen
import app.moneytracker.ui.operations.NewOperationKind
import app.moneytracker.ui.operations.PaidForMeEditScreen
import app.moneytracker.ui.debts.DebtsScreen
import app.moneytracker.ui.debts.PersonDebtsScreen
import app.moneytracker.ui.operations.RefundEditScreen
import app.moneytracker.ui.operations.TransferEditScreen
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
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
import app.moneytracker.ui.receipts.ReceiptIntakeScreen
import app.moneytracker.ui.receipts.ReceiptScreen
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import app.moneytracker.ui.accounts.AccountsScreen
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
    TopLevel(PricesRoute, PricesRoute::class, R.string.nav_prices, Icons.Filled.QrCodeScanner),
    TopLevel(MoreRoute, MoreRoute::class, R.string.nav_more, Icons.Filled.MoreHoriz),
)

@Composable
fun AppRoot(
    openAdd: Boolean = false,
    onOpenAddHandled: () -> Unit = {},
    openCapture: Long = 0,
    onOpenCaptureHandled: () -> Unit = {},
    openReceipt: String? = null,
    receiptsEnabled: Boolean? = null,
    onEnableReceipts: () -> Unit = {},
    onOpenReceiptHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    // Ссылка из «Поделиться»: при выключенной функции сначала спрашиваем, включить ли.
    if (openReceipt != null && receiptsEnabled == false) {
        AlertDialog(
            onDismissRequest = onOpenReceiptHandled,
            title = { Text(stringResource(R.string.receipts_disabled_title)) },
            text = { Text(stringResource(R.string.receipts_disabled_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onEnableReceipts()
                    navController.navigate(ReceiptIntakeRoute(text = openReceipt))
                    onOpenReceiptHandled()
                }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = { TextButton(onClick = onOpenReceiptHandled) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    LaunchedEffect(openReceipt, receiptsEnabled) {
        if (openReceipt != null && receiptsEnabled == true) {
            navController.navigate(ReceiptIntakeRoute(text = openReceipt))
            onOpenReceiptHandled()
        }
    }
    LaunchedEffect(openCapture) {
        if (openCapture != 0L) {
            navController.navigate(ExpenseEditRoute(captureId = openCapture))
            onOpenCaptureHandled()
        }
    }
    LaunchedEffect(openAdd) {
        if (openAdd) {
            navController.navigate(ExpenseEditRoute())
            onOpenAddHandled()
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    // «Цены» — только при включённых «Чеках и ценах».
    val tabs = topLevel.filter { it.route != PricesRoute || receiptsEnabled == true }
    val showBottomBar = tabs.any { t -> destination?.hierarchy?.any { it.hasRoute(t.routeClass) } == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { item ->
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
        // Потребляем только нижний отступ (его уже занял NavigationBar), чтобы внутренние Scaffold'ы
        // не добавили его второй раз. Верхний (строка состояния) не трогаем — его учитывает TopAppBar
        // каждого экрана; если «съесть» и его, заголовки уезжают под часы и значки.
        NavHost(
            navController = navController,
            startDestination = ExpensesRoute,
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding())),
        ) {
            composable<ExpensesRoute> {
                ExpenseListScreen(
                    onAdd = { navController.navigate(ExpenseEditRoute()) },
                    onEdit = { navController.navigate(ExpenseEditRoute(it)) },
                    onEditIncome = { navController.navigate(IncomeEditRoute(it)) },
                    onEditRefund = { navController.navigate(RefundEditRoute(it)) },
                    onEditTransfer = { navController.navigate(TransferEditRoute(it)) },
                    onEditPaidForMe = { navController.navigate(PaidForMeEditRoute(it)) },
                    onOpenPerson = { navController.navigate(PersonDebtsRoute(it)) },
                    onPending = { navController.navigate(PendingCapturesRoute) },
                )
            }
            composable<ReceiptIntakeRoute> {
                ReceiptIntakeScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReceipt = { id ->
                        navController.navigate(ReceiptRoute(id)) { popUpTo<ReceiptIntakeRoute> { inclusive = true } }
                    },
                    onOpenEditor = { route ->
                        navController.navigate(route) { popUpTo<ReceiptIntakeRoute> { inclusive = true } }
                    },
                )
            }
            composable<ReceiptRoute> {
                ReceiptScreen(
                    onBack = { navController.popBackStack() },
                    onOpenExpense = { navController.navigate(ExpenseEditRoute(id = it)) },
                    onLink = { navController.navigate(ReceiptIntakeRoute(receiptId = it)) },
                    onOpenProduct = { navController.navigate(ProductRoute(it)) },
                )
            }
            composable<PricesRoute> {
                PricesScreen(
                    onScanned = { navController.navigate(ReceiptIntakeRoute(text = it)) },
                    onOpenProduct = { navController.navigate(ProductRoute(it)) },
                    onStores = { navController.navigate(StoresRoute) },
                    onReceipts = { navController.navigate(ReceiptsRoute(it)) },
                )
            }
            composable<ProductRoute> {
                ProductScreen(
                    onBack = { navController.popBackStack() },
                    onOpenExpense = { navController.navigate(ExpenseEditRoute(id = it)) },
                    onOpenReceipt = { navController.navigate(ReceiptRoute(it)) },
                )
            }
            composable<AutoCaptureRoute> {
                AutoCaptureScreen(
                    onBack = { navController.popBackStack() },
                    onPending = { navController.navigate(PendingCapturesRoute) },
                    onDebug = { navController.navigate(CaptureDebugRoute) },
                )
            }
            composable<PendingCapturesRoute> {
                PendingCapturesScreen(onBack = { navController.popBackStack() }, onEdit = { navController.navigate(ExpenseEditRoute(captureId = it)) })
            }
            composable<CaptureDebugRoute> { CaptureDebugScreen(onBack = { navController.popBackStack() }) }
            composable<ExpenseEditRoute> {
                ExpenseEditScreen(
                    onClose = { navController.popBackStack() },
                    onSwitchKind = { navController.switchNewOperation(it) },
                    onRefund = { navController.navigate(RefundEditRoute(expenseId = it)) { popUpTo<ExpenseEditRoute> { inclusive = true } } },
                    onOpenReceipt = { navController.navigate(ReceiptRoute(it)) },
                )
            }
            composable<IncomeEditRoute> {
                IncomeEditScreen(onClose = { navController.popBackStack() }, onSwitchKind = { navController.switchNewOperation(it) })
            }
            composable<RefundEditRoute> { RefundEditScreen(onClose = { navController.popBackStack() }) }
            composable<TransferEditRoute> {
                TransferEditScreen(onClose = { navController.popBackStack() }, onSwitchKind = { navController.switchNewOperation(it) })
            }
            composable<PaidForMeEditRoute> {
                PaidForMeEditScreen(onClose = { navController.popBackStack() }, onSwitchKind = { navController.switchNewOperation(it) })
            }
            composable<DebtsRoute> {
                DebtsScreen(
                    onBack = { navController.popBackStack() },
                    onPerson = { navController.navigate(PersonDebtsRoute(it)) },
                    onAddPaidForMe = { navController.navigate(PaidForMeEditRoute()) },
                )
            }
            composable<PersonDebtsRoute> { PersonDebtsScreen(onBack = { navController.popBackStack() }) }
            composable<IncomeCategoriesRoute> { IncomeCategoriesScreen(onBack = { navController.popBackStack() }) }
            composable<AnalyticsRoute> {
                AnalyticsScreen(
                    onCategory = { id, from, to ->
                        navController.navigate(FilteredExpensesRoute(categoryId = id, fromEpochDay = from.toEpochDay(), toEpochDay = to.toEpochDay()))
                    },
                    onAccount = { id, from, to ->
                        navController.navigate(FilteredExpensesRoute(accountId = id, fromEpochDay = from.toEpochDay(), toEpochDay = to.toEpochDay()))
                    },
                )
            }
            composable<FilteredExpensesRoute> {
                ExpenseListScreen(
                    onAdd = { navController.navigate(ExpenseEditRoute()) },
                    onEdit = { navController.navigate(ExpenseEditRoute(it)) },
                    onEditIncome = { navController.navigate(IncomeEditRoute(it)) },
                    onEditRefund = { navController.navigate(RefundEditRoute(it)) },
                    onEditTransfer = { navController.navigate(TransferEditRoute(it)) },
                    onEditPaidForMe = { navController.navigate(PaidForMeEditRoute(it)) },
                    onOpenPerson = { navController.navigate(PersonDebtsRoute(it)) },
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
                    onAccounts = { navController.navigate(AccountsRoute) },
                    onDebts = { navController.navigate(DebtsRoute) },
                    onIncomeCategories = { navController.navigate(IncomeCategoriesRoute) },
                    onAutoCapture = { navController.navigate(AutoCaptureRoute) },
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
            composable<AccountsRoute> { AccountsScreen(onBack = { navController.popBackStack() }) }
            composable<DataRoute> { DataScreen(onBack = { navController.popBackStack() }) }
            composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
            composable<RatesRoute> { RatesScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

/** Переключение типа новой операции заменяет текущий экран добавления, а не кладёт второй поверх. */
private fun NavHostController.switchNewOperation(kind: NewOperationKind) {
    val route: Any = when (kind) {
        NewOperationKind.EXPENSE -> ExpenseEditRoute()
        NewOperationKind.INCOME -> IncomeEditRoute()
        NewOperationKind.TRANSFER -> TransferEditRoute()
        NewOperationKind.DEBT -> PaidForMeEditRoute()
    }
    val current = currentBackStackEntry?.destination?.id ?: return
    navigate(route) { popUpTo(current) { inclusive = true } }
}
