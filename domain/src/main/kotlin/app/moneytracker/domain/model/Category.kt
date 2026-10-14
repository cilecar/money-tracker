package app.moneytracker.domain.model

data class Category(
    val id: Long = 0,
    val name: String,
    /**
     * Ключ категории по умолчанию («food», «groceries»…). Пока он задан, UI берёт
     * название из ресурсов текущей локали; при переименовании ключ сбрасывается.
     */
    val defaultKey: String? = null,
    val iconKey: String,
    /** ARGB. */
    val color: Int,
    /** null — категория верхнего уровня. Допускается только один уровень вложенности. */
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)
