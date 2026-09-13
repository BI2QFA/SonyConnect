package com.bi2qfa.sonyconnect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.ui.theme.CircleTint
import com.bi2qfa.sonyconnect.ui.theme.Motion
































@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = if (onBack == null) 20.dp else 4.dp,
                end = 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                
                
                
                val titleIn = Motion.spatialDefault<IntOffset>()
                val titleOut = Motion.spatialFast<IntOffset>()
                val fadeInSpec = Motion.effectsDefault<Float>()
                val fadeOutSpec = Motion.effectsFast<Float>()
                AnimatedContent(
                    targetState = title to subtitle,
                    transitionSpec = {
                        (
                            slideInVertically(titleIn) { it / 5 } + fadeIn(fadeInSpec)
                            ).togetherWith(
                            slideOutVertically(titleOut) { -it / 5 } + fadeOut(fadeOutSpec),
                        )
                    },
                    label = "headerTitle",
                ) { (t, sub) ->
                    Column {
                        Text(
                            t,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            actions?.invoke(this)
        }
    }
}


@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        
        
        
        
        modifier = modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}















@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
}









@Composable
fun segmentShape(index: Int, count: Int): Shape =
    ListItemDefaults.segmentedShapes(index = index, count = count).shape












@Composable
fun segmentSurfaceColor(): Color =
    ListItemDefaults.segmentedColors()
        .copy(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        .containerColor





@Composable
fun Segment(
    index: Int = 0,
    count: Int = 1,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = segmentShape(index, count)
    val color = segmentSurfaceColor()
    if (onClick != null) {
        
        
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            content = { Column(content = content) },
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            content = { Column(content = content) },
        )
    }
}







@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsGroup(modifier) {
        Segment(index = 0, count = 1, onClick = onClick, content = content)
    }
}


@Composable
fun IconCircle(
    iconRes: Int,
    tint: CircleTint,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        Modifier.size(size).background(tint.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint.content,
        )
    }
}


@Composable
fun Chevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}























@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    support: String? = null,
    supportColor: Color? = null,
    








    supportMaxLines: Int = 3,
    leading: (@Composable () -> Unit)? = null,
    
    
    
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val colors = ListItemDefaults.segmentedColors()
        .copy(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    
    
    
    val headline: @Composable () -> Unit = {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            
            
            
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val supporting: (@Composable () -> Unit)? = support?.let { text ->
        {
            Text(
                text,
                
                
                maxLines = supportMaxLines,
                overflow = TextOverflow.Ellipsis,
                
                color = supportColor ?: LocalContentColor.current,
            )
        }
    }
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            shapes = shapes,
            colors = colors,
            content = headline,
        )
    } else {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            shapes = shapes,
            colors = colors,
            content = headline,
        )
    }
}






@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    support: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        support = support,
        modifier = modifier,
        index = index,
        count = count,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                thumbContent = if (checked) {
                    {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null,
            )
        },
    )
}





@Composable
fun ExpressiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val pad = PaddingValues(horizontal = 28.dp, vertical = 8.dp)
    val label: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            enabled = enabled,
            shape = CircleShape,
            contentPadding = pad,
            content = label,
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            enabled = enabled,
            shape = CircleShape,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(),
            content = label,
        )
    }
}


@Composable
fun RowIconAction(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
    }
}


@Composable
fun HintText(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

