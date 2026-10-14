package app.moneytracker.ui.common

/**
 * Палитра категорий: оттенки Material 300 — достаточно насыщенные, чтобы различаться
 * на диаграмме, и достаточно светлые, чтобы белый/чёрный значок на них читался.
 */
object CategoryColors {
    val palette: List<Int> = listOf(
        0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF9575CD,
        0xFF7986CB, 0xFF64B5F6, 0xFF4FC3F7, 0xFF4DD0E1,
        0xFF4DB6AC, 0xFF81C784, 0xFFAED581, 0xFFDCE775,
        0xFFFFD54F, 0xFFFFB74D, 0xFFFF8A65, 0xFFA1887F,
        0xFF90A4AE, 0xFFE0E0E0,
    ).map { it.toInt() }
}
