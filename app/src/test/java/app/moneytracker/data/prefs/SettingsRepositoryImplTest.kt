package app.moneytracker.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.moneytracker.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SettingsRepositoryImplTest {

    @Test
    fun `unreadable settings file falls back to defaults instead of crashing`() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk error") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = throw IOException("disk error")
        }
        assertEquals(AppSettings(), SettingsRepositoryImpl(broken).settings.first())
    }

    /** DataStore в памяти: updateData применяет преобразование к текущим настройкам. */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `receipts flag defaults to false and persists`() = runTest {
        val repo = SettingsRepositoryImpl(MemoryDataStore())
        assertEquals(false, repo.settings.first().receiptsEnabled)
        repo.setReceiptsEnabled(true)
        assertEquals(true, repo.settings.first().receiptsEnabled)
    }
}
