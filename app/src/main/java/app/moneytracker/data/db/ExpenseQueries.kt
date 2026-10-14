package app.moneytracker.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ExpenseFilter

/**
 * Собирает SQL для списка расходов по фильтру.
 *
 * Почему @RawQuery, а не обычный @Query с `(:x IS NULL OR …)`: в фильтре есть
 * необязательные списки (категории, способы оплаты, теги), а `IN (:list)` с пустым
 * списком в Room не выразить без отдельного флага на каждый. Динамический SQL проще,
 * а параметры всё равно передаются через `?` — без склейки значений в строку.
 *
 * Текстовый поиск сюда не входит: LIKE и lower() в SQLite не понимают кириллицу,
 * поэтому он делается в Kotlin поверх результата (см. [matchesQuery]).
 */
object ExpenseQueries {

    fun build(filter: ExpenseFilter): SimpleSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (filter.categoryIds.isNotEmpty()) {
            where += "category_id IN (${placeholders(filter.categoryIds.size)})"
            args.addAll(filter.categoryIds)
        }
        if (filter.accountIds.isNotEmpty()) {
            where += "account_id IN (${placeholders(filter.accountIds.size)})"
            args.addAll(filter.accountIds)
        }
        if (filter.tags.isNotEmpty()) {
            where += """
                EXISTS (SELECT 1 FROM expense_tag et JOIN tag t ON t.id = et.tag_id
                        WHERE et.expense_id = expense.id AND t.name_key IN (${placeholders(filter.tags.size)}))
            """.trimIndent()
            args.addAll(filter.tags.map { it.lowercase() })
        }
        filter.minAmount?.let {
            where += "${amountColumn(it.currency)} >= ?"
            args += it.minor
        }
        filter.maxAmount?.let {
            where += "${amountColumn(it.currency)} <= ?"
            args += it.minor
        }
        filter.from?.let {
            where += "local_date >= ?"
            args += it.toEpochDay()
        }
        filter.to?.let {
            where += "local_date <= ?"
            args += it.toEpochDay()
        }

        val sql = buildString {
            append("SELECT * FROM expense")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY occurred_at DESC, id DESC")
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /**
     * Сумма в валюте фильтра: по снапшоту, а для расходов без курса — исходная сумма,
     * если валюта совпадает (иначе NULL и условие не выполняется).
     * Имя колонки берётся из enum, не из пользовательского ввода, — инъекции нет.
     */
    private fun amountColumn(currency: CurrencyCode): String {
        val converted = "${currency.name.lowercase()}_minor"
        return "COALESCE($converted, CASE WHEN currency = '${currency.name}' THEN amount_minor END)"
    }

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

    fun matchesQuery(comment: String?, tags: List<String>, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return comment?.lowercase()?.contains(q) == true || tags.any { it.lowercase().contains(q) }
    }
}
