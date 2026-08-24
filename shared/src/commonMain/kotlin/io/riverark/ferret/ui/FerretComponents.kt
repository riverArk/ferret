package io.riverark.ferret.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val cardShape = RoundedCornerShape(24.dp)
private val controlShape = RoundedCornerShape(16.dp)

@Composable
fun FerretScreen(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(horizontal = FerretSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FerretSpacing.md),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FerretTopBar(title: String, navigation: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { navigation?.invoke() },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun FerretPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled, shape = controlShape) {
        Row(horizontalArrangement = Arrangement.spacedBy(FerretSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            Text(text)
        }
    }
}

@Composable
fun FerretSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled, shape = controlShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(horizontalArrangement = Arrangement.spacedBy(FerretSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            Text(text)
        }
    }
}

@Composable
fun FerretTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled) { Text(text) }
}

@Composable
fun FerretDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick,
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled,
        shape = controlShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) { Text(text) }
}

@Composable
fun FerretCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (onClick == null) Card(modifier, cardShape, colors, elevation, border) { Column(Modifier.padding(FerretSpacing.md), content = content) }
    else Card(onClick, modifier, true, cardShape, colors, elevation, border) { Column(Modifier.padding(FerretSpacing.md), content = content) }
}

@Composable
fun FerretListRow(
    title: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    FerretCard(modifier.fillMaxWidth().heightIn(min = 56.dp), onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FerretSpacing.sm)) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun FerretStatusChip(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.primary, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Text(text, Modifier.padding(horizontal = FerretSpacing.sm, vertical = FerretSpacing.xxs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun FerretDataBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FerretSpacing.xs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun FerretLoadingState(message: String) {
    Column(Modifier.fillMaxWidth().padding(FerretSpacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FerretSpacing.md)) {
        CircularProgressIndicator()
        Text(message)
    }
}

@Composable
fun FerretErrorState(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(FerretSpacing.lg).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FerretSpacing.md)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (action != null && onAction != null) FerretSecondaryButton(action, onAction)
    }
}

@Composable
fun FerretEmptyState(title: String, message: String, content: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(FerretSpacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FerretSpacing.sm)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content?.invoke()
    }
}
