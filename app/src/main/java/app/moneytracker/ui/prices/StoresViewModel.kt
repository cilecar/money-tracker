package app.moneytracker.ui.prices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.receipt.ChainWithStores
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ReceiptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StoresUiState(val chains: List<ChainWithStores>, val categories: List<Category>)

@HiltViewModel
class StoresViewModel @Inject constructor(
    private val receipts: ReceiptRepository,
    categories: CategoryRepository,
) : ViewModel() {
    val state: StateFlow<StoresUiState?> = combine(receipts.observeChains(), categories.observeAll(includeArchived = true)) { chains, cats ->
        StoresUiState(chains, cats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setShortName(chainId: Long, name: String) = viewModelScope.launch { receipts.setChainShortName(chainId, name) }

    fun setCategory(chainId: Long, categoryId: Long?) = viewModelScope.launch { receipts.setChainDefaultCategory(chainId, categoryId) }
}
