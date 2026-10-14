package app.moneytracker.data.io

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DefaultData
import app.moneytracker.data.db.DefaultNames
import app.moneytracker.data.db.entity.BalanceAdjustmentEntity
import app.moneytracker.data.db.entity.BudgetAlertEntity
import app.moneytracker.data.db.entity.BudgetEntity
import app.moneytracker.data.db.entity.CategoryEntity
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.data.db.entity.IncomeCategoryEntity
import app.moneytracker.data.db.entity.AccountEntity
import app.moneytracker.data.db.entity.RecurringExpenseEntity
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Frequency
import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.Refund
import app.moneytracker.domain.model.Transfer
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.model.RateSource
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.model.ThemeMode
import app.moneytracker.domain.repository.BackupFormatException
import app.moneytracker.domain.repository.BackupFormatException.Reason
import app.moneytracker.domain.repository.CsvErrorReason
import app.moneytracker.domain.repository.CsvImportResult
import app.moneytracker.domain.repository.CsvRowError
import app.moneytracker.domain.repository.DataTransfer
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.JsonImportResult
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.RunRecurringUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataTransferImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val rates: ExchangeRateRepository,
    private val defaultNames: DefaultNames,
    private val json: Json,
    private val clock: Clock,
    private val runRecurring: RunRecurringUseCase,
) : DataTransfer {

    private val zone: ZoneId get() = clock.zone

    // ---------------------------------------------------------------- CSV

    override suspend fun exportCsv(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val categories = db.categoryDao().getAll(true).associateBy { it.id }
        val methods = db.accountDao().getAll(true).associateBy { it.id }
        val expenses = db.expenseDao().getAll().map { it.toDomain() }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(Csv.BOM.code)
        Csv.writeRow(writer, CSV_HEADER)
        for (e in expenses) {
            val category = categories[e.categoryId]
            val parent = category?.parentId?.let(categories::get)
            Csv.writeRow(
                writer,
                listOf(
                    e.occurredAt.atZone(zone).format(CSV_DATE_TIME),
                    e.amount.amount.toPlainString(),
                    e.amount.currency.name,
                    (parent ?: category)?.let { defaultNames.resolve(it.defaultKey, it.name) },
                    if (parent != null) category?.name else null,
                    methods[e.accountId]?.let { defaultNames.resolve(it.defaultKey, it.name) },
                    e.comment,
                    e.tags.joinToString(";"),
                    e.converted?.rsdMinor?.let { Money(it, CurrencyCode.RSD).amount.toPlainString() },
                    e.converted?.eurMinor?.let { Money(it, CurrencyCode.EUR).amount.toPlainString() },
                    e.converted?.rubMinor?.let { Money(it, CurrencyCode.RUB).amount.toPlainString() },
                    e.rates?.rsdPerEur?.toPlainString(),
                    e.rates?.rubPerEur?.toPlainString(),
                    e.rates?.date?.toString(),
                ),
            )
        }
        writer.flush()
        expenses.size
    }

    override suspend fun importCsv(input: InputStream): CsvImportResult = withContext(Dispatchers.IO) {
        val rows = Csv.read(input.bufferedReader(Charsets.UTF_8))
        val header = rows.firstOrNull()?.fields?.map { it.trim().lowercase() } ?: throw BackupFormatException(Reason.NO_HEADER)
        fun col(name: String) = header.indexOf(name).takeIf { it >= 0 }
        val iDate = col("date") ?: throw BackupFormatException(Reason.NO_HEADER)
        val iAmount = col("amount") ?: throw BackupFormatException(Reason.NO_HEADER)
        val iCurrency = col("currency") ?: throw BackupFormatException(Reason.NO_HEADER)
        val iCategory = col("category") ?: throw BackupFormatException(Reason.NO_HEADER)
        val iSub = col("subcategory")
        // «payment_method» — заголовок файлов, выгруженных до появления счетов.
        val iMethod = col("account") ?: col("payment_method")
        val iComment = col("comment")
        val iTags = col("tags")
        val iRateRsd = col("rate_rsd_per_eur")
        val iRateRub = col("rate_rub_per_eur")
        val iRateDate = col("rate_date")

        val now = clock.instant()
        val currentRates = rates.current()?.snapshot
        val categories = CategoryResolver(db.categoryDao().getAll(true).toMutableList())
        val methods = db.accountDao().getAll(true).toMutableList()
        val defaultMethodId = settings.settings.first().defaultAccountId?.takeIf { id -> methods.any { it.id == id } }
            ?: methods.firstOrNull { !it.archived }?.id
            ?: methods.first().id
        val existingKeys = db.expenseDao().getAll().map { dedupeKey(it.expense.occurredAt, it.expense.amountMinor, it.expense.currency, it.expense.categoryId) }.toMutableSet()

        var imported = 0
        var duplicates = 0
        var createdMethods = 0
        // Начальная дата нового счёта — самый ранний расход из файла, иначе его же расходы не попали бы в баланс.
        val createdOpening = mutableMapOf<Long, LocalDate>()
        val errors = mutableListOf<CsvRowError>()

        db.withTransaction {
            for (row in rows.drop(1)) {
                fun get(i: Int?) = i?.let { row.fields.getOrNull(it) }?.trim().orEmpty()
                fun fail(reason: CsvErrorReason) { errors += CsvRowError(row.line, reason) }

                val occurredAt = parseDateTime(get(iDate)) ?: run { fail(CsvErrorReason.BAD_DATE); null } ?: continue
                val currency = CurrencyCode.entries.firstOrNull { it.name.equals(get(iCurrency), ignoreCase = true) }
                    ?: run { fail(CsvErrorReason.BAD_CURRENCY); null } ?: continue
                val amount = Money.parse(get(iAmount), currency)?.takeIf { it.minor > 0 }
                    ?: run { fail(CsvErrorReason.BAD_AMOUNT); null } ?: continue
                val categoryName = get(iCategory).ifEmpty { null } ?: run { fail(CsvErrorReason.MISSING_CATEGORY); null } ?: continue

                val snapshot = if (get(iRateRsd).isNotEmpty() || get(iRateRub).isNotEmpty()) {
                    val rsd = get(iRateRsd).replace(',', '.').toBigDecimalOrNull()
                    val rub = get(iRateRub).replace(',', '.').toBigDecimalOrNull()
                    val date = get(iRateDate).ifEmpty { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: occurredAt.atZone(zone).toLocalDate()
                    if (rsd == null || rub == null || rsd.signum() <= 0 || rub.signum() <= 0) {
                        fail(CsvErrorReason.BAD_RATE)
                        continue
                    }
                    RateSnapshot(rsd, rub, date)
                } else {
                    currentRates
                }

                val categoryId = categories.resolve(categoryName, get(iSub).ifEmpty { null })

                val methodName = get(iMethod)
                val methodId = if (methodName.isEmpty()) {
                    defaultMethodId
                } else {
                    methods.firstOrNull { matches(it.name, it.defaultKey, methodName) }?.id ?: run {
                        val entity = AccountEntity(
                            id = 0,
                            name = methodName,
                            type = AccountType.CASH,
                            currency = currency,
                            openingBalanceMinor = 0,
                            openingDate = occurredAt.atZone(zone).toLocalDate(),
                            defaultKey = null,
                            sortOrder = methods.size,
                            archived = false,
                        )
                        val id = db.accountDao().upsert(entity)
                        methods += entity.copy(id = id)
                        createdOpening[id] = entity.openingDate
                        createdMethods++
                        id
                    }
                }
                createdOpening.computeIfPresent(methodId) { _, first -> minOf(first, occurredAt.atZone(zone).toLocalDate()) }

                val key = dedupeKey(occurredAt, amount.minor, currency, categoryId)
                if (!existingKeys.add(key)) {
                    duplicates++
                    continue
                }
                val expense = Expense.create(
                    amount = amount,
                    categoryId = categoryId,
                    accountId = methodId,
                    occurredAt = occurredAt,
                    zone = zone,
                    rates = snapshot,
                    now = now,
                    comment = get(iComment),
                    tags = get(iTags).split(';', ','),
                )
                db.expenseDao().insertWithTags(expense.toEntity(), expense.tags)
                imported++
            }
            createdOpening.forEach { (id, date) ->
                methods.first { it.id == id }.let { db.accountDao().upsert(it.copy(openingDate = date)) }
            }
        }
        CsvImportResult(imported, duplicates, categories.created, createdMethods, errors)
    }

    /** Поиск категории по имени (и по переводу для категорий по умолчанию) с созданием недостающих. */
    private inner class CategoryResolver(private val all: MutableList<CategoryEntity>) {
        var created = 0
            private set

        suspend fun resolve(name: String, subName: String?): Long {
            val top = find(name, parentId = null) ?: create(name, parent = null)
            if (subName == null) return top.id
            return (find(subName, parentId = top.id) ?: create(subName, parent = top)).id
        }

        // Активная категория предпочтительнее архивной с тем же именем.
        private fun find(name: String, parentId: Long?) = all
            .filter { it.parentId == parentId && matches(it.name, it.defaultKey, name) }
            .minByOrNull { if (it.archived) 1 else 0 }

        private suspend fun create(name: String, parent: CategoryEntity?): CategoryEntity {
            val entity = CategoryEntity(
                id = 0,
                name = name,
                defaultKey = null,
                iconKey = parent?.iconKey ?: "more_horiz",
                color = parent?.color ?: 0xFF90A4AE.toInt(),
                parentId = parent?.id,
                sortOrder = all.count { it.parentId == parent?.id },
                archived = false,
            )
            val saved = entity.copy(id = db.categoryDao().upsert(entity))
            all += saved
            created++
            return saved
        }
    }

    private fun matches(storedName: String, defaultKey: String?, query: String): Boolean =
        storedName.equals(query, ignoreCase = true) || defaultNames.resolve(defaultKey, storedName).equals(query, ignoreCase = true)

    /** Дубль = та же минута, сумма, валюта и категория. Повторный импорт своего же экспорта ничего не добавит. */
    private fun dedupeKey(at: Instant, minor: Long, currency: CurrencyCode, categoryId: Long) =
        "${at.epochSecond / 60}|$minor|$currency|$categoryId"

    /** Понимает наш формат, ISO с часовым поясом, только дату и привычный «dd.MM.yyyy». */
    private fun parseDateTime(text: String): Instant? {
        if (text.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(text).toInstant() }
        for (f in DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, f).atZone(zone).toInstant()
            } catch (_: DateTimeParseException) {
            }
        }
        for (f in DATE_FORMATS) {
            try {
                // Только дата — ставим полдень, чтобы смена пояса не сдвинула день.
                return LocalDate.parse(text, f).atTime(LocalTime.NOON).atZone(zone).toInstant()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    // ---------------------------------------------------------------- JSON

    override suspend fun exportJson(out: OutputStream) = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        val m = db.maintenanceDao()
        // Все чтения — в одной транзакции: иначе запись между запросами (новая категория и расход в ней,
        // регулярный платёж) даст файл, где расход ссылается на категорию, которой в файле нет.
        val dto = db.withTransaction {
            BackupDto(
                format = BackupDto.FORMAT,
                version = BackupDto.VERSION,
                exportedAt = clock.instant().toString(),
                settings = SettingsDto(s.inputCurrency.name, s.displayCurrency.name, s.defaultAccountId, s.themeMode.name, s.dynamicColor),
                categories = db.categoryDao().getAll(true).map {
                    CategoryDto(it.id, it.name, it.defaultKey, it.iconKey, it.color, it.parentId, it.sortOrder, it.archived)
                },
                accounts = db.accountDao().getAll(true).map {
                    AccountDto(
                        it.id, it.name, it.type.name, it.currency.name, it.openingBalanceMinor, it.openingDate.toString(),
                        it.defaultKey, it.sortOrder, it.archived,
                    )
                },
                expenses = db.expenseDao().getAll().map { it.toDomain() }.map { e ->
                    ExpenseDto(
                        id = e.id,
                        amountMinor = e.amount.minor,
                        currency = e.amount.currency.name,
                        categoryId = e.categoryId,
                        accountId = e.accountId,
                        occurredAt = e.occurredAt.toString(),
                        localDate = e.localDate.toString(),
                        comment = e.comment,
                        tags = e.tags,
                        rates = e.rates?.toDto(),
                        amountRsdMinor = e.converted?.rsdMinor,
                        amountEurMinor = e.converted?.eurMinor,
                        amountRubMinor = e.converted?.rubMinor,
                        recurringId = e.recurringId,
                        accountAmountMinor = e.accountAmountMinor,
                        createdAt = e.createdAt.toString(),
                        updatedAt = e.updatedAt.toString(),
                    )
                },
                budgets = db.budgetDao().getAll().map { BudgetDto(it.id, it.categoryId, it.limitMinor, it.currency.name) },
                budgetAlerts = m.allBudgetAlerts().map { BudgetAlertDto(it.budgetId, it.yearMonth, it.level) },
                recurring = m.allRecurring().map {
                    RecurringDto(
                        it.id, it.amountMinor, it.currency.name, it.categoryId, it.accountId, it.comment, it.tags,
                        it.frequency.name, it.interval, it.dayOfMonth, it.startDate.toString(), it.endDate?.toString(),
                        it.nextRunDate.toString(), it.active,
                    )
                },
                exchangeRates = m.allRates().map {
                    RateDto(it.date.toString(), it.currency.name, it.source.name, it.perEur.toPlainString(), it.fetchedAt.toString())
                },
                balanceAdjustments = m.allAdjustments().map {
                    BalanceAdjustmentDto(it.id, it.accountId, it.amountMinor, it.occurredAt.toString(), it.localDate.toString(), it.comment, it.createdAt.toString())
                },
                incomeCategories = db.incomeCategoryDao().getAll(true).map {
                    IncomeCategoryDto(it.id, it.name, it.defaultKey, it.iconKey, it.color, it.sortOrder, it.archived)
                },
                incomes = db.incomeDao().getAll().map { it.toDomain() }.map { i ->
                    IncomeDto(
                        i.id, i.amount.minor, i.amount.currency.name, i.accountId, i.categoryId, i.occurredAt.toString(), i.localDate.toString(),
                        i.comment, i.rates?.toDto(), i.accountAmountMinor, i.createdAt.toString(), i.updatedAt.toString(),
                    )
                },
                refunds = db.refundDao().getAll().map { it.toDomain() }.map { r ->
                    RefundDto(
                        r.id, r.amount.minor, r.amount.currency.name, r.accountId, r.expenseId, r.categoryId, r.occurredAt.toString(),
                        r.localDate.toString(), r.comment, r.rates?.toDto(), r.accountAmountMinor, r.createdAt.toString(), r.updatedAt.toString(),
                    )
                },
                transfers = db.transferDao().getAll().map { it.toDomain() }.map { t ->
                    TransferDto(
                        t.id, t.fromAccountId, t.toAccountId, t.fromAmount.minor, t.fromAmount.currency.name, t.toAmount.minor,
                        t.toAmount.currency.name, t.feeExpenseId, t.occurredAt.toString(), t.localDate.toString(), t.comment,
                        t.apiRates?.toDto(), t.createdAt.toString(), t.updatedAt.toString(),
                    )
                },
            )
        }
        out.write(json.encodeToString(BackupDto.serializer(), dto).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    override suspend fun importJson(input: InputStream): JsonImportResult = withContext(Dispatchers.IO) {
        val dto = try {
            json.decodeFromString(BackupDto.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw BackupFormatException(Reason.NOT_A_BACKUP, e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException(Reason.NOT_A_BACKUP, e)
        }
        if (dto.format != BackupDto.FORMAT) throw BackupFormatException(Reason.NOT_A_BACKUP)
        if (dto.version > BackupDto.VERSION) throw BackupFormatException(Reason.NEWER_VERSION)

        // Сначала всё разбираем и проверяем, и только потом трогаем базу:
        // битый файл не должен оставить пользователя с пустым приложением.
        val parsed = try {
            parse(dto)
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Exception) {
            throw BackupFormatException(Reason.INCONSISTENT, e)
        }

        try {
            // Под замком регулярных платежей: запуск, начатый до импорта, иначе дописал бы расходы
            // по старым шаблонам и сдвинул бы даты у новых с тем же id.
            runRecurring.exclusive {
                db.withTransaction {
                    db.maintenanceDao().deleteAll()
                    // Родители раньше подкатегорий — из-за внешнего ключа parent_id.
                    parsed.categories.sortedBy { if (it.parentId == null) 0 else 1 }.forEach { db.categoryDao().upsert(it) }
                    parsed.methods.forEach { db.accountDao().upsert(it) }
                    parsed.adjustments.forEach { db.accountDao().insertAdjustment(it) }
                    parsed.incomeCategories.forEach { db.incomeCategoryDao().upsert(it) }
                    parsed.incomes.forEach { db.incomeDao().insert(it.toEntity()) }
                    parsed.recurring.forEach { db.recurringDao().upsert(it) }
                    parsed.expenses.forEach { db.expenseDao().insertWithTags(it.toEntity(), it.tags) }
                    parsed.refunds.forEach { db.refundDao().insert(it.toEntity()) }
                    parsed.transfers.forEach { db.transferDao().upsert(it.toEntity()) }
                    parsed.budgets.forEach { db.budgetDao().save(it) }
                    parsed.alerts.forEach { db.budgetDao().insertAlert(it) }
                    if (parsed.rates.isNotEmpty()) db.exchangeRateDao().upsert(parsed.rates)
                }
            }
        } catch (e: SQLiteConstraintException) {
            // Известные нарушения ловит parse(); это страховка: транзакция откатилась, данные целы,
            // и пользователь видит «файл несогласован», а не падение приложения.
            throw BackupFormatException(Reason.INCONSISTENT, e)
        }

        dto.settings.let { s ->
            CurrencyCode.entries.firstOrNull { it.name == s.inputCurrency }?.let { settings.setInputCurrency(it) }
            CurrencyCode.entries.firstOrNull { it.name == s.displayCurrency }?.let { settings.setDisplayCurrency(it) }
            ThemeMode.entries.firstOrNull { it.name == s.themeMode }?.let { settings.setThemeMode(it) }
            settings.setDynamicColor(s.dynamicColor)
            settings.setDefaultAccount(s.defaultAccountId?.takeIf { id -> parsed.methods.any { it.id == id } })
        }
        JsonImportResult(parsed.expenses.size, parsed.categories.size, parsed.methods.size, parsed.budgets.size, parsed.recurring.size)
    }

    private class Parsed(
        val categories: List<CategoryEntity>,
        val methods: List<AccountEntity>,
        val expenses: List<Expense>,
        val budgets: List<BudgetEntity>,
        val alerts: List<BudgetAlertEntity>,
        val recurring: List<RecurringExpenseEntity>,
        val rates: List<ExchangeRateEntity>,
        val adjustments: List<BalanceAdjustmentEntity>,
        val incomeCategories: List<IncomeCategoryEntity>,
        val incomes: List<Income>,
        val refunds: List<Refund>,
        val transfers: List<Transfer>,
    )

    private fun parse(dto: BackupDto): Parsed {
        fun currency(code: String) = CurrencyCode.valueOf(code)
        // Повтор id не дошёл бы до проверок ниже: upsert молча склеил бы записи, а insert упал бы на PRIMARY KEY.
        fun unique(ids: List<Any>) = ids.toSet().also { if (it.size != ids.size) inconsistent() }
        // Нулевой курс — деление на ноль при пересчёте, отрицательная сумма — отрицательные итоги.
        fun positive(n: Long) = n.also { if (it <= 0) inconsistent() }
        fun positive(n: BigDecimal) = n.also { if (it.signum() <= 0) inconsistent() }

        val categories = dto.categories.map {
            CategoryEntity(it.id, it.name, it.defaultKey, it.iconKey, it.color, it.parentId, it.sortOrder, it.archived)
        }
        val categoryIds = unique(categories.map { it.id })
        categories.forEach { c ->
            val parentId = c.parentId ?: return@forEach
            val parent = categories.firstOrNull { it.id == parentId } ?: inconsistent()
            if (parent.parentId != null) inconsistent()
        }
        val today = LocalDate.now(clock)
        val methods = dto.accounts.map { a ->
            val own = dto.expenses.filter { it.accountId == a.id }
            AccountEntity(
                id = a.id,
                name = a.name,
                type = AccountType.valueOf(a.type),
                // Файл версии 1: как миграция 1→2 — самая частая валюта расходов счёта, затем регулярных, иначе RSD.
                currency = a.currency?.let(::currency)
                    ?: own.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key?.let(::currency)
                    ?: dto.recurring.firstOrNull { it.accountId == a.id }?.currency?.let(::currency)
                    ?: CurrencyCode.RSD,
                openingBalanceMinor = a.openingBalanceMinor,
                openingDate = a.openingDate?.let(LocalDate::parse) ?: own.minOfOrNull { LocalDate.parse(it.localDate) } ?: today,
                defaultKey = a.defaultKey,
                sortOrder = a.sortOrder,
                archived = a.archived,
            )
        }
        val methodIds = unique(methods.map { it.id })
        if (categories.none { !it.archived } || methods.none { !it.archived }) inconsistent()

        val recurring = dto.recurring.map {
            if (it.categoryId !in categoryIds || it.accountId !in methodIds) inconsistent()
            // Через доменную модель: её require (interval >= 1, день 1..31) срабатывает здесь, при импорте,
            // а не при каждом чтении шаблона после него.
            RecurringExpense(
                id = it.id,
                amount = Money(positive(it.amountMinor), currency(it.currency)),
                categoryId = it.categoryId,
                accountId = it.accountId,
                comment = it.comment,
                tags = it.tags,
                frequency = Frequency.valueOf(it.frequency),
                interval = it.interval,
                dayOfMonth = it.dayOfMonth,
                startDate = LocalDate.parse(it.startDate),
                endDate = it.endDate?.let(LocalDate::parse),
                nextRunDate = LocalDate.parse(it.nextRunDate),
                active = it.active,
            ).toEntity()
        }
        val recurringIds = unique(recurring.map { it.id })

        val expenses = dto.expenses.map {
            if (it.categoryId !in categoryIds || it.accountId !in methodIds) inconsistent()
            val amount = Money(positive(it.amountMinor), currency(it.currency))
            val snapshot = it.rates?.toSnapshot()
            Expense(
                id = it.id,
                amount = amount,
                categoryId = it.categoryId,
                accountId = it.accountId,
                occurredAt = Instant.parse(it.occurredAt),
                localDate = LocalDate.parse(it.localDate),
                comment = it.comment,
                tags = Expense.normalizeTags(it.tags),
                rates = snapshot,
                // Пересчитываем по снапшоту, а не доверяем полям файла: так суммы гарантированно согласованы.
                converted = snapshot?.convertAll(amount),
                recurringId = it.recurringId?.takeIf { id -> id in recurringIds },
                accountAmountMinor = it.accountAmountMinor,
                createdAt = Instant.parse(it.createdAt),
                updatedAt = Instant.parse(it.updatedAt),
            )
        }
        unique(expenses.map { it.id })
        val budgets = dto.budgets.map {
            if (it.categoryId !in categoryIds) inconsistent()
            BudgetEntity(it.id, it.categoryId, positive(it.limitMinor), currency(it.currency))
        }
        val budgetIds = unique(budgets.map { it.id })
        // Бюджет на категорию один: второй BudgetDao.save слил бы с первым, и его отметки упали бы на внешнем ключе.
        unique(budgets.map { it.categoryId })
        val alerts = dto.budgetAlerts.filter { it.budgetId in budgetIds }.map {
            YearMonth.parse(it.yearMonth)
            BudgetAlertEntity(it.budgetId, it.yearMonth, it.level)
        }
        val rates = dto.exchangeRates.map {
            ExchangeRateEntity(LocalDate.parse(it.date), currency(it.currency), RateSource.valueOf(it.source), positive(BigDecimal(it.perEur)), Instant.parse(it.fetchedAt))
        }
        val adjustments = dto.balanceAdjustments.map {
            if (it.accountId !in methodIds) inconsistent()
            BalanceAdjustmentEntity(it.id, it.accountId, it.amountMinor, Instant.parse(it.occurredAt), LocalDate.parse(it.localDate), it.comment, Instant.parse(it.createdAt))
        }
        unique(adjustments.map { it.id })

        // Файл без категорий доходов (до их появления) — создаём набор по умолчанию.
        val incomeCategories = dto.incomeCategories.map {
            IncomeCategoryEntity(it.id, it.name, it.defaultKey, it.iconKey, it.color, it.sortOrder, it.archived)
        }.ifEmpty {
            DefaultData.incomeCategories.mapIndexed { i, c -> IncomeCategoryEntity(i + 1L, context.getString(c.nameRes), c.key, c.iconKey, c.color, i, false) }
        }
        val incomeCategoryIds = unique(incomeCategories.map { it.id })
        if (incomeCategories.none { !it.archived }) inconsistent()
        val expenseIds = expenses.map { it.id }.toSet()
        val incomes = dto.incomes.map {
            if (it.accountId !in methodIds || it.categoryId !in incomeCategoryIds) inconsistent()
            val amount = Money(positive(it.amountMinor), currency(it.currency))
            val snapshot = it.rates?.toSnapshot()
            Income(
                id = it.id, amount = amount, accountId = it.accountId, categoryId = it.categoryId,
                occurredAt = Instant.parse(it.occurredAt), localDate = LocalDate.parse(it.localDate), comment = it.comment,
                rates = snapshot, converted = snapshot?.convertAll(amount), accountAmountMinor = it.accountAmountMinor,
                createdAt = Instant.parse(it.createdAt), updatedAt = Instant.parse(it.updatedAt),
            )
        }
        unique(incomes.map { it.id })
        val refunds = dto.refunds.map {
            if (it.accountId !in methodIds || it.categoryId !in categoryIds) inconsistent()
            val amount = Money(positive(it.amountMinor), currency(it.currency))
            val snapshot = it.rates?.toSnapshot()
            Refund(
                id = it.id, amount = amount, accountId = it.accountId,
                expenseId = it.expenseId?.takeIf { id -> id in expenseIds }, categoryId = it.categoryId,
                occurredAt = Instant.parse(it.occurredAt), localDate = LocalDate.parse(it.localDate), comment = it.comment,
                rates = snapshot, converted = snapshot?.convertAll(amount), accountAmountMinor = it.accountAmountMinor,
                createdAt = Instant.parse(it.createdAt), updatedAt = Instant.parse(it.updatedAt),
            )
        }
        unique(refunds.map { it.id })
        val accountCurrency = methods.associate { it.id to it.currency }
        val transfers = dto.transfers.map {
            if (it.fromAccountId !in methodIds || it.toAccountId !in methodIds || it.fromAccountId == it.toAccountId) inconsistent()
            val fromAmount = Money(positive(it.fromAmountMinor), currency(it.fromCurrency))
            val toAmount = Money(positive(it.toAmountMinor), currency(it.toCurrency))
            // Суммы перевода обязаны быть в валютах своих счетов — на этом держится баланс.
            if (fromAmount.currency != accountCurrency[it.fromAccountId] || toAmount.currency != accountCurrency[it.toAccountId]) inconsistent()
            Transfer(
                id = it.id, fromAccountId = it.fromAccountId, toAccountId = it.toAccountId, fromAmount = fromAmount, toAmount = toAmount,
                feeExpenseId = it.feeExpenseId?.takeIf { id -> id in expenseIds },
                occurredAt = Instant.parse(it.occurredAt), localDate = LocalDate.parse(it.localDate), comment = it.comment,
                apiRates = it.apiRates?.toSnapshot(), createdAt = Instant.parse(it.createdAt), updatedAt = Instant.parse(it.updatedAt),
            )
        }
        unique(transfers.map { it.id })
        return Parsed(categories, methods, expenses, budgets, alerts, recurring, rates, adjustments, incomeCategories, incomes, refunds, transfers)
    }

    private fun inconsistent(): Nothing = throw BackupFormatException(Reason.INCONSISTENT)

    private fun RateSnapshot.toDto() = RatesDto(rsdPerEur.toPlainString(), rubPerEur.toPlainString(), date.toString())

    private fun RatesDto.toSnapshot() = RateSnapshot(BigDecimal(rsdPerEur), BigDecimal(rubPerEur), LocalDate.parse(date))

    // ---------------------------------------------------------------- Файл базы

    override suspend fun backupDatabase(out: OutputStream) = withContext(Dispatchers.IO) {
        val source = context.getDatabasePath(AppDatabase.NAME)
        val dir = File(context.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, AppDatabase.NAME)
        try {
            // WAL: свежие изменения лежат в -wal, и checkpoint не всегда может их перенести — открытый
            // читатель держит старый снимок. Поэтому копируем .db вместе с -wal, держа пишущую транзакцию:
            // пока она открыта, никто не закоммитит и не сбросит -wal, и пара файлов согласована.
            db.withTransaction {
                source.copyTo(copy)
                File(source.path + "-wal").takeIf { it.exists() }?.copyTo(File(copy.path + "-wal"))
            }
            // Копия сама применяет свой -wal; journal_mode=DELETE вливает его в .db и удаляет — получаем один файл.
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { c ->
                c.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            }
            copy.inputStream().use { it.copyTo(out) }
            out.flush()
        } finally {
            dir.deleteRecursively()
        }
    }

    override suspend fun restoreDatabase(input: InputStream) = withContext(Dispatchers.IO) {
        val target = context.getDatabasePath(AppDatabase.NAME)
        // Готовим файл рядом с базой: нехватка места или ошибка записи всплывут, пока живая база ещё открыта,
        // а в том же каталоге подмена — атомарное переименование, без окна с наполовину записанным файлом.
        val staged = File(target.path + ".restore")
        try {
            staged.outputStream().use { input.copyTo(it) }
            validateBackup(staged)
            db.close()
            // После close SQLite уже перенёс -wal в .db; старые -wal/-shm не должны примениться к новому файлу.
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            staged.delete()
            File(staged.path + "-wal").delete()
            File(staged.path + "-shm").delete()
            File(staged.path + "-journal").delete()
        }
        Unit
    }

    /** Проверки до подмены: это SQLite, это наша схема, версия не новее, файл не повреждён. */
    internal fun validateBackup(file: File) {
        val header = file.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (!header.contentEquals(SQLITE_HEADER)) throw BackupFormatException(Reason.NOT_A_BACKUP)

        val current = db.openHelper.readableDatabase
        val currentVersion = current.version
        val currentHash = current.query("SELECT identity_hash FROM room_master_table").use { c -> if (c.moveToFirst()) c.getString(0) else null }

        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { backup ->
                if (backup.version > currentVersion) throw BackupFormatException(Reason.NEWER_VERSION)
                val hash = backup.rawQuery("SELECT identity_hash FROM room_master_table", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                // Та же версия — схема обязана совпасть.
                if (hash == null || (backup.version == currentVersion && hash != currentHash)) throw BackupFormatException(Reason.NOT_A_BACKUP)
                // Более старую версию обновят миграции Room при открытии — но только если цепочка до текущей есть.
                // Иначе Room упадёт на каждом запуске уже после подмены файла.
                if (!AppDatabase.hasMigrationPath(backup.version, currentVersion)) throw BackupFormatException(Reason.NOT_A_BACKUP)
                val integrity = backup.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (integrity != "ok") throw BackupFormatException(Reason.INCONSISTENT)
            }
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Exception) {
            throw BackupFormatException(Reason.NOT_A_BACKUP, e)
        }
    }

    companion object {
        val CSV_HEADER = listOf(
            "date", "amount", "currency", "category", "subcategory", "account", "comment", "tags",
            "amount_rsd", "amount_eur", "amount_rub", "rate_rsd_per_eur", "rate_rub_per_eur", "rate_date",
        )
        private val CSV_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val DATE_TIME_FORMATS = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "dd.MM.yyyy HH:mm")
            .map(DateTimeFormatter::ofPattern)
        private val DATE_FORMATS = listOf("yyyy-MM-dd", "dd.MM.yyyy").map(DateTimeFormatter::ofPattern)
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
