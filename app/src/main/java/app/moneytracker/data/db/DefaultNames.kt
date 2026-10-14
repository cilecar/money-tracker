package app.moneytracker.data.db

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Локализованные названия для записей по умолчанию — для ViewModel, где нет Compose.
 * Нужно, чтобы понять, переименовал ли пользователь «Продукты»: если имя в поле совпадает
 * с переводом, defaultKey сохраняется, и при смене языка название тоже сменится.
 */
class DefaultNames @Inject constructor(@ApplicationContext private val context: Context) {
    fun resolve(defaultKey: String?, storedName: String): String =
        defaultKey?.let(DefaultData::nameResFor)?.let(context::getString) ?: storedName
}
