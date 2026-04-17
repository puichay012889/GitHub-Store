package zed.rainxch.details.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StatItem(
    label: String,
    stat: Int,
    modifier: Modifier = Modifier,
) {
    StatItem(label = label, stat = stat.toLong(), modifier = modifier)
}

@Composable
fun StatItem(
    label: String,
    stat: Long,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                softWrap = false,
            )

            Text(
                text = formatCount(stat),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
fun TextStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                softWrap = false,
            )

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private fun formatCount(count: Long): String =
    when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
