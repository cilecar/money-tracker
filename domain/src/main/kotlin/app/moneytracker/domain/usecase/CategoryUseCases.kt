package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CategoryUsage
import app.moneytracker.domain.repository.CategoryRepository
import javax.inject.Inject

enum class CategoryError {
    EMPTY_NAME,
    DUPLICATE_NAME,

    /** Родитель сам является подкатегорией, это та же категория или она архивная. */
    INVALID_PARENT,

    /** У категории есть подкатегории — её нельзя сделать подкатегорией (только один уровень). */
    HAS_CHILDREN,

    /** Нельзя удалить или архивировать последнюю активную категорию — не во что будет записывать расходы. */
    LAST_ACTIVE,

    /** На категорию ссылаются расходы — нужно выбрать, куда их перенести. */
    NEEDS_TARGET,
    INVALID_TARGET,
}

sealed interface CategoryResult {
    data class Success(val id: Long) : CategoryResult
    data class Error(val error: CategoryError) : CategoryResult
}

/** Чистые правила без БД — их удобно тестировать. */
object CategoryRules {

    /** Сравнение имён: без учёта регистра и пробелов по краям. */
    fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    fun validate(category: Category, all: List<Category>): CategoryError? {
        if (category.name.isBlank()) return CategoryError.EMPTY_NAME

        val parentId = category.parentId
        if (parentId != null) {
            val parent = all.firstOrNull { it.id == parentId }
            if (parent == null || parent.id == category.id || parent.parentId != null || parent.archived) {
                return CategoryError.INVALID_PARENT
            }
            if (category.id != 0L && all.any { it.parentId == category.id }) return CategoryError.HAS_CHILDREN
        }

        // Имена уникальны среди «соседей» одного уровня: «Еда › Кафе» и «Развлечения › Кафе» допустимы.
        val duplicate = all.any {
            it.id != category.id && it.parentId == parentId && !it.archived && sameName(it.name, category.name)
        }
        return if (duplicate) CategoryError.DUPLICATE_NAME else null
    }

    /** Куда можно перенести расходы удаляемой категории: любая активная, кроме неё самой. */
    fun moveTargets(id: Long, all: List<Category>): List<Category> =
        all.filter { it.id != id && !it.archived }
}

class SaveCategoryUseCase @Inject constructor(private val repository: CategoryRepository) {
    suspend operator fun invoke(category: Category): CategoryResult {
        val clean = category.copy(name = category.name.trim())
        CategoryRules.validate(clean, repository.getAll())?.let { return CategoryResult.Error(it) }
        val toSave = if (clean.id == 0L) clean.copy(sortOrder = repository.nextSortOrder(clean.parentId)) else clean
        return CategoryResult.Success(repository.upsert(toSave))
    }
}

class DeleteCategoryUseCase @Inject constructor(private val repository: CategoryRepository) {

    suspend fun usage(id: Long): CategoryUsage = repository.usage(id)

    suspend operator fun invoke(id: Long, moveTo: Long? = null): CategoryResult {
        val all = repository.getAll()
        val category = all.firstOrNull { it.id == id } ?: return CategoryResult.Success(id)
        val usage = repository.usage(id)

        if (usage.children > 0) return CategoryResult.Error(CategoryError.HAS_CHILDREN)
        if (!category.archived && all.count { !it.archived } <= 1) return CategoryResult.Error(CategoryError.LAST_ACTIVE)
        if (usage.needsTarget) {
            if (moveTo == null) return CategoryResult.Error(CategoryError.NEEDS_TARGET)
            if (CategoryRules.moveTargets(id, all).none { it.id == moveTo }) {
                return CategoryResult.Error(CategoryError.INVALID_TARGET)
            }
        }
        repository.delete(id, moveTo.takeIf { usage.needsTarget })
        return CategoryResult.Success(id)
    }
}

/**
 * Архив вместо удаления: история не трогается, категория просто пропадает из выбора.
 * Архивация родителя архивирует и подкатегории; возврат подкатегории возвращает и родителя.
 */
class ArchiveCategoryUseCase @Inject constructor(private val repository: CategoryRepository) {
    suspend operator fun invoke(id: Long, archived: Boolean): CategoryResult {
        val all = repository.getAll()
        val category = all.firstOrNull { it.id == id } ?: return CategoryResult.Success(id)
        if (archived) {
            val affected = listOf(id) + all.filter { it.parentId == id }.map { it.id }
            if (all.none { !it.archived && it.id !in affected }) return CategoryResult.Error(CategoryError.LAST_ACTIVE)
            repository.setArchived(affected, true)
        } else {
            repository.setArchived(listOfNotNull(id, category.parentId), false)
        }
        return CategoryResult.Success(id)
    }
}
