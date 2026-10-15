package app.moneytracker.data.repository

import app.moneytracker.data.db.dao.AccountDao
import app.moneytracker.data.db.dao.UsdtConversionRow
import app.moneytracker.data.db.snapshot
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountBalance
import app.moneytracker.domain.model.AccountUsage
import app.moneytracker.domain.model.BalanceAdjustment
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AccountRepositoryImpl @Inject constructor(
    private val dao: AccountDao,
) : AccountRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Account>> =
        dao.observeAll(includeArchived).map { list -> list.map { it.toDomain() } }

    override fun observeBalances(): Flow<List<AccountBalance>> =
        combine(dao.observeAll(true), dao.observeBalances(), dao.observeUsdtConversions()) { accounts, rows, usdtRows ->
            val byId = rows.associateBy { it.accountId }
            val usdt = usdtRows.groupBy { it.accountId }.mapValues { (_, list) -> convertToUsdt(list) }
            accounts.map { entity ->
                val account = entity.toDomain()
                val row = byId[account.id]
                val (extraMinor, extraUnconverted) = usdt[account.id] ?: (0L to 0)
                AccountBalance(
                    account,
                    Money((row?.balanceMinor ?: account.openingBalance.minor) + extraMinor, account.currency),
                    (row?.unconverted ?: 0) + extraUnconverted,
                )
            }
        }

    /**
     * Сумма операций USDT-счёта в USDT по их снапшотам — как SQL делает с rsd/eur/rub_minor: каждая операция
     * округляется отдельно. Без снапшота или без курса USDT в нём (записи до USDT) операция не вошла.
     * @return сумма в минорных USDT и число непересчитанных операций.
     */
    private fun convertToUsdt(rows: List<UsdtConversionRow>): Pair<Long, Int> {
        var sum = 0L
        var unconverted = 0
        for (row in rows) {
            val converted = snapshot(row.rateRsdPerEur, row.rateRubPerEur, row.rateDate, row.rateUsdtPerEur)
                ?.convertOrNull(Money(row.amountMinor, row.currency), CurrencyCode.USDT)
            if (converted == null) unconverted++ else sum += row.sign * converted.minor
        }
        return sum to unconverted
    }

    override suspend fun getAll(includeArchived: Boolean): List<Account> = dao.getAll(includeArchived).map { it.toDomain() }

    override suspend fun get(id: Long): Account? = dao.get(id)?.toDomain()

    override suspend fun upsert(account: Account): Long {
        val rowId = dao.upsert(account.toEntity())
        return if (account.id != 0L) account.id else rowId
    }

    override suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived)

    override suspend fun usage(id: Long) = AccountUsage(
        expenses = dao.countExpenses(id) + dao.countIncomesAndRefunds(id),
        recurring = dao.countRecurring(id),
        adjustments = dao.countAdjustments(id),
        transfers = dao.countTransfers(id),
    )

    override suspend fun hasOperations(id: Long): Boolean = dao.hasOperations(id)

    override suspend fun delete(id: Long, moveTo: Long?) = dao.deleteMovingReferences(id, moveTo)

    override suspend fun nextSortOrder(): Int = dao.nextSortOrder()

    override suspend fun addAdjustment(adjustment: BalanceAdjustment): Long = dao.insertAdjustment(adjustment.toEntity())

    override fun observeAdjustments(accountId: Long): Flow<List<BalanceAdjustment>> =
        combine(dao.observeAll(true), dao.observeAdjustments(accountId)) { accounts, list ->
            val currency = accounts.firstOrNull { it.id == accountId }?.currency ?: return@combine emptyList()
            list.map { it.toDomain(currency) }
        }

    override suspend fun deleteAdjustment(id: Long) = dao.deleteAdjustment(id)
}
