package app.moneytracker.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.moneytracker.data.db.DefaultData
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.PaymentMethod

/** Название с учётом локали: для нетронутых категорий по умолчанию — из ресурсов. */
@Composable
fun Category.displayName(): String =
    defaultKey?.let(DefaultData::nameResFor)?.let { stringResource(it) } ?: name

@Composable
fun PaymentMethod.displayName(): String =
    defaultKey?.let(DefaultData::nameResFor)?.let { stringResource(it) } ?: name

/** «Еда › Кафе» для подкатегории. */
@Composable
fun categoryTitle(category: Category?, parent: Category?): String = when {
    category == null -> "—"
    parent == null -> category.displayName()
    else -> "${parent.displayName()} › ${category.displayName()}"
}

@Composable
fun CategoryAvatar(category: Category?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = category?.let { Color(it.color) } ?: MaterialTheme.colorScheme.surfaceVariant
    // Цвет значка подбираем под яркость фона, чтобы он читался и на светлых, и на тёмных цветах.
    val content = if (color.luminance() > 0.5f) Color.Black.copy(alpha = 0.8f) else Color.White
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.forKey(category?.iconKey.orEmpty()),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Заглушка для разделов будущих этапов. */
@Composable
fun ComingSoon(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
