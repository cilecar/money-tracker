package app.moneytracker.ui.receipts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Сканер QR из Google Play Services: свой экран камеры и разрешение на камеру не нужны.
 * Возвращает функцию запуска. Отмена пользователем — ни [onResult], ни [onError].
 */
@Composable
fun rememberQrScanner(onResult: (String) -> Unit, onError: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val error by rememberUpdatedState(onError)
    return remember(context) {
        {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                // QR фискального чека плотный (ссылка ~900 символов) и мелкий: сканер сам приближает.
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode -> barcode.rawValue?.let(result) ?: error() }
                .addOnFailureListener { error() }
        }
    }
}
