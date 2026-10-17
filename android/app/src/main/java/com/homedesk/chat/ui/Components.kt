package com.homedesk.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homedesk.chat.net.LinkState
import com.homedesk.chat.net.ModelInfo
import com.homedesk.chat.net.SettingCheck
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.FieldShapes
import com.homedesk.chat.ui.theme.LocalChrome

@Composable
fun linkColor(state: LinkState): Color {
    val chrome = LocalChrome.current
    return when (state) {
        LinkState.Ok -> chrome.success
        LinkState.Warn -> chrome.warn
        LinkState.Bad -> MaterialTheme.colorScheme.error
        LinkState.Unknown -> MaterialTheme.colorScheme.outline
    }
}

@Composable
fun ConnectionRoute(
    desk: LinkState,
    gpu: LinkState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteNode("Phone", Icons.Filled.Smartphone, LinkState.Ok, Modifier.weight(1f))
        RouteBridge(desk)
        RouteNode("Desk", Icons.Filled.DesktopWindows, desk, Modifier.weight(1f))
        RouteBridge(gpu)
        RouteNode("GPU", Icons.Filled.Memory, gpu, Modifier.weight(1f))
    }
}

@Composable
private fun RouteNode(
    label: String,
    icon: ImageVector,
    state: LinkState,
    modifier: Modifier = Modifier,
) {
    val tint = linkColor(state)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f))
                .border(2.dp, tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Text(
            label,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RouteBridge(state: LinkState) {
    Box(
        modifier = Modifier
            .padding(bottom = 18.dp)
            .width(28.dp)
            .height(3.dp)
            .clip(CircleShape)
            .background(linkColor(state)),
    )
}

@Composable
fun CheckList(checks: List<SettingCheck>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        checks.forEach { check ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = when (check.state) {
                        LinkState.Ok -> Icons.Filled.CheckCircle
                        LinkState.Warn -> Icons.Filled.Warning
                        LinkState.Bad -> Icons.Filled.Error
                        LinkState.Unknown -> Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = linkColor(check.state),
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(check.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        check.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun FieldCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = FieldShapes.card
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (accent != null) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accent != null) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(4.dp)
                        .heightIn(min = 36.dp)
                        .background(accent, FieldShapes.chip),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(FieldMetrics.screenPadding),
                content = content,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(top = FieldMetrics.space8, bottom = FieldMetrics.space4),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
fun DeskButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(FieldMetrics.primaryTouch),
        shape = FieldShapes.button,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        }
        Text(text)
    }
}

@Composable
fun DeskOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(FieldMetrics.minTouch),
        shape = FieldShapes.button,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text)
    }
}

@Composable
fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun BusyLine(visible: Boolean, modifier: Modifier = Modifier) {
    if (visible) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}

@Composable
fun ModelPicker(
    models: List<ModelInfo>,
    selected: String,
    manual: String,
    message: String?,
    onManual: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    Text(
        "The PC keeps one model loaded so the next reply starts quickly.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (models.isEmpty()) {
        Text(
            "Installed models show up here after a connection test. You can still type a name.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            models.forEach { model ->
                val isSelected = model.name == selected
                val accent = if (model.loaded) LocalChrome.current.success else MaterialTheme.colorScheme.outline
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FieldShapes.search)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = FieldShapes.search,
                        )
                        .clickable { onSelect(model.name) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(model.name, style = MaterialTheme.typography.titleSmall)
                        val meta = listOfNotNull(model.parameterSize, model.quantization)
                            .joinToString(" · ")
                        val loaded = when {
                            model.loaded && meta.isNotBlank() -> "$meta · In memory"
                            model.loaded -> "In memory"
                            else -> meta
                        }
                        if (loaded.isNotBlank()) {
                            Text(
                                loaded,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (model.loaded) LocalChrome.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
    OutlinedTextField(
        value = manual,
        onValueChange = onManual,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        label = { Text("Model name") },
        placeholder = { Text("qwen2.5:7b") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
    DeskButton(
        text = "Use this model",
        onClick = { onSelect(manual) },
        modifier = Modifier.padding(top = 12.dp),
        icon = Icons.Filled.Memory,
    )
    if (!message.isNullOrBlank()) {
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
