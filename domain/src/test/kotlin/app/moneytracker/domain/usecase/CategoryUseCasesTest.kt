package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeCategoryRepository
import app.moneytracker.domain.model.Category
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryUseCasesTest {

    private fun cat(id: Long, name: String, parentId: Long? = null, archived: Boolean = false, sort: Int = 0) =
        Category(id = id, name = name, iconKey = "more_horiz", color = 0, parentId = parentId, archived = archived, sortOrder = sort)

    private val repo = FakeCategoryRepository(
        listOf(
            cat(1, "Еда", sort = 0),
            cat(2, "Кафе", parentId = 1),
            cat(3, "Транспорт", sort = 1),
            cat(4, "Старое", archived = true, sort = 2),
        ),
    )
    private val save = SaveCategoryUseCase(repo)
    private val delete = DeleteCategoryUseCase(repo)
    private val archive = ArchiveCategoryUseCase(repo)

    private fun CategoryResult.error() = (this as? CategoryResult.Error)?.error

    @Test
    fun `new category goes to the end of its level`() = runTest {
        val id = (save(cat(0, "  Подарки ")) as CategoryResult.Success).id
        val saved = repo.get(id)!!
        assertEquals("Подарки", saved.name)
        assertEquals(3, saved.sortOrder)
    }

    @Test
    fun `name rules`() = runTest {
        assertEquals(CategoryError.EMPTY_NAME, save(cat(0, "   ")).error())
        assertEquals(CategoryError.DUPLICATE_NAME, save(cat(0, "еДа")).error())
        // Тот же текст на другом уровне — можно
        assertTrue(save(cat(0, "Кафе")) is CategoryResult.Success)
        // Имя архивной категории можно занять
        assertTrue(save(cat(0, "старое")) is CategoryResult.Success)
        // Переименование самой себя в то же имя — не дубликат
        assertTrue(save(cat(1, "ЕДА")) is CategoryResult.Success)
    }

    @Test
    fun `only one nesting level`() = runTest {
        assertEquals(CategoryError.INVALID_PARENT, save(cat(0, "Латте", parentId = 2)).error()) // родитель — подкатегория
        assertEquals(CategoryError.INVALID_PARENT, save(cat(3, "Транспорт", parentId = 3)).error()) // сама себе родитель
        assertEquals(CategoryError.INVALID_PARENT, save(cat(0, "X", parentId = 4)).error()) // архивный родитель
        assertEquals(CategoryError.HAS_CHILDREN, save(cat(1, "Еда", parentId = 3)).error()) // у «Еды» есть «Кафе»
        assertTrue(save(cat(3, "Транспорт", parentId = 1)) is CategoryResult.Success)
    }

    @Test
    fun `unused category is deleted right away`() = runTest {
        assertTrue(delete(3) is CategoryResult.Success)
        assertNull(repo.get(3))
    }

    @Test
    fun `used category needs a valid target and moves expenses`() = runTest {
        repo.expenseCounts[3] = 5
        repo.expenseCounts[1] = 2
        assertEquals(CategoryError.NEEDS_TARGET, delete(3).error())
        assertEquals(CategoryError.INVALID_TARGET, delete(3, moveTo = 3).error())
        assertEquals(CategoryError.INVALID_TARGET, delete(3, moveTo = 4).error()) // в архивную нельзя
        assertTrue(delete(3, moveTo = 2) is CategoryResult.Success)
        assertEquals(5, repo.expenseCounts[2])
    }

    @Test
    fun `parent with subcategories cannot be deleted`() = runTest {
        assertEquals(CategoryError.HAS_CHILDREN, delete(1, moveTo = 3).error())
    }

    @Test
    fun `last active category is protected`() = runTest {
        val single = FakeCategoryRepository(listOf(cat(1, "Только"), cat(2, "Архив", archived = true)))
        assertEquals(CategoryError.LAST_ACTIVE, DeleteCategoryUseCase(single)(1).error())
        assertEquals(CategoryError.LAST_ACTIVE, ArchiveCategoryUseCase(single)(1, true).error())
        // архивную удалить можно
        assertTrue(DeleteCategoryUseCase(single)(2) is CategoryResult.Success)
    }

    @Test
    fun `archiving parent archives children and restoring child restores parent`() = runTest {
        archive(1, true)
        assertTrue(repo.get(1)!!.archived && repo.get(2)!!.archived)

        archive(2, false)
        assertTrue(!repo.get(1)!!.archived && !repo.get(2)!!.archived)
    }

    @Test
    fun `restoring from archive keeps names unique`() = runTest {
        // «Старое» в архиве, за это время создали новую активную «Старое».
        save(cat(0, "старое "))
        assertEquals(CategoryError.DUPLICATE_NAME, archive(4, false).error())
        assertTrue(repo.get(4)!!.archived)

        // Возврат подкатегории возвращает и родителя — его имя тоже проверяем.
        archive(1, true)
        save(cat(0, "Еда"))
        assertEquals(CategoryError.DUPLICATE_NAME, archive(2, false).error())
        assertTrue(repo.get(1)!!.archived && repo.get(2)!!.archived)
    }

    @Test
    fun `archiving parent fails if it would leave nothing active`() = runTest {
        val r = FakeCategoryRepository(listOf(cat(1, "Еда"), cat(2, "Кафе", parentId = 1)))
        assertEquals(CategoryError.LAST_ACTIVE, ArchiveCategoryUseCase(r)(1, true).error())
    }
}
