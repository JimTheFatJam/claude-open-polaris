package io.github.jimthefatjam.polaris.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.jimthefatjam.polaris.core.model.DataQuality
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors

@Composable
fun PolarisCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = PolarisColors.Surface.copy(alpha = 0.82f), contentColor = PolarisColors.Starlight)
    val border = BorderStroke(1.dp, PolarisColors.Outline.copy(alpha = 0.6f))
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = colors, border = border, shape = shape) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Card(modifier = modifier, colors = colors, border = border, shape = shape) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Small pill that states where a value came from (forecast, estimated, calculated, …). */
@Composable
fun QualityBadge(q: DataQuality, modifier: Modifier = Modifier) {
    val color = when (q) {
        DataQuality.MODEL_CURRENT, DataQuality.FORECAST -> PolarisColors.Accent
        DataQuality.ESTIMATED -> PolarisColors.Fair
        DataQuality.CALCULATED -> PolarisColors.Excellent
        DataQuality.USER_PROVIDED -> PolarisColors.AccentWarm
        DataQuality.UNAVAILABLE -> PolarisColors.Faint
    }
    Surface(modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color.copy(alpha = 0.5f))) {
        Text(q.label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.size(10.dp).background(color, CircleShape))
}

enum class BannerKind { INFO, WARNING, ERROR }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    val (color, icon: ImageVector) = when (kind) {
        BannerKind.INFO -> PolarisColors.Accent to Icons.Outlined.Info
        BannerKind.WARNING -> PolarisColors.Fair to Icons.Outlined.CloudOff
        BannerKind.ERROR -> PolarisColors.Unsuitable to Icons.Outlined.ErrorOutline
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.12f), border = BorderStroke(1.dp, color.copy(alpha = 0.4f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = PolarisColors.Starlight, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = PolarisColors.Starlight, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = PolarisColors.Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
