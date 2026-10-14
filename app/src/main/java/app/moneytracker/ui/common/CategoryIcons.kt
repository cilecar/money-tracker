package app.moneytracker.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import app.moneytracker.domain.model.AccountType

/**
 * В БД хранится строковый ключ иконки, а не ресурс/ImageVector: ключ стабилен
 * между версиями приложения и переносится в экспорт/импорт.
 */
object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "restaurant" to Icons.Filled.Restaurant,
        "local_cafe" to Icons.Filled.LocalCafe,
        "fastfood" to Icons.Filled.Fastfood,
        "local_bar" to Icons.Filled.LocalBar,
        "shopping_cart" to Icons.Filled.ShoppingCart,
        "local_grocery_store" to Icons.Filled.LocalGroceryStore,
        "shopping_bag" to Icons.Filled.ShoppingBag,
        "directions_bus" to Icons.Filled.DirectionsBus,
        "local_taxi" to Icons.Filled.LocalTaxi,
        "directions_car" to Icons.Filled.DirectionsCar,
        "local_gas_station" to Icons.Filled.LocalGasStation,
        "train" to Icons.Filled.Train,
        "flight" to Icons.Filled.Flight,
        "home" to Icons.Filled.Home,
        "bolt" to Icons.Filled.Bolt,
        "water_drop" to Icons.Filled.WaterDrop,
        "wifi" to Icons.Filled.Wifi,
        "smartphone" to Icons.Filled.Smartphone,
        "theaters" to Icons.Filled.Theaters,
        "sports_esports" to Icons.Filled.SportsEsports,
        "music_note" to Icons.Filled.MusicNote,
        "celebration" to Icons.Filled.Celebration,
        "beach_access" to Icons.Filled.BeachAccess,
        "medical_services" to Icons.Filled.MedicalServices,
        "local_pharmacy" to Icons.Filled.LocalPharmacy,
        "fitness_center" to Icons.Filled.FitnessCenter,
        "spa" to Icons.Filled.Spa,
        "checkroom" to Icons.Filled.Checkroom,
        "subscriptions" to Icons.Filled.Subscriptions,
        "menu_book" to Icons.AutoMirrored.Filled.MenuBook,
        "school" to Icons.Filled.School,
        "child_care" to Icons.Filled.ChildCare,
        "pets" to Icons.Filled.Pets,
        "card_giftcard" to Icons.Filled.CardGiftcard,
        "build" to Icons.Filled.Build,
        "work" to Icons.Filled.Work,
        "savings" to Icons.Filled.Savings,
        "receipt" to Icons.AutoMirrored.Filled.ReceiptLong,
        "more_horiz" to Icons.Filled.MoreHoriz,
    )

    fun forKey(key: String): ImageVector = all[key] ?: Icons.Filled.MoreHoriz

    fun forPaymentType(type: AccountType): ImageVector = when (type) {
        AccountType.CASH -> Icons.Filled.Payments
        AccountType.CARD -> Icons.Filled.CreditCard
        AccountType.TRANSFER -> Icons.Filled.SwapHoriz
    }
}
