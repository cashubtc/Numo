package com.electricdreams.shellshock.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.electricdreams.shellshock.ui.theme.CashAppTypography
import com.electricdreams.shellshock.ui.theme.Gray700
import com.electricdreams.shellshock.ui.theme.White

// Custom Colors for Bottom Bar
private val BottomBarBackground = Color(0xFF171717)
private val IconCircleInactive = Color(0xFF414141)
private val IconCircleActive = Color(0xFFFFFFFF)
private val IconInactive = Color(0xFFB0B0B0)
private val IconActive = Color(0xFF111111)

@Composable
fun CashAppTopBar(
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    onCloseClick: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    backgroundColor: Color = White,
    contentColor: Color = Gray700
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(backgroundColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left Action (Back or Close)
        if (onBackClick != null) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = contentColor
                )
            }
        } else if (onCloseClick != null) {
            IconButton(onClick = onCloseClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = contentColor
                )
            }
        } else {
            // Spacer for alignment if no left action
            Spacer(modifier = Modifier.size(48.dp))
        }

        // Title
        if (title != null) {
            Text(
                text = title,
                style = CashAppTypography.titleMedium,
                color = contentColor
            )
        }

        // Right Actions
        Row {
            if (actions != null) {
                actions()
            } else {
                // Spacer for alignment
                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }
}

@Composable
fun CashAppBottomBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Container with custom shape and shadow
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp) // 64dp + 16dp padding
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                spotColor = Color.Black.copy(alpha = 0.3f)
            ),
        color = BottomBarBackground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp) // Padding for gesture area
                .padding(horizontal = 16.dp), // Inset from edges
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                BottomBarItem(
                    item = item,
                    isSelected = index == selectedIndex,
                    onClick = { onItemSelected(index) }
                )
            }
        }
    }
}

@Composable
fun BottomBarItem(
    item: BottomNavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "scale"
    )

    val circleColor by animateColorAsState(
        targetValue = if (isSelected) IconCircleActive else IconCircleInactive,
        label = "circleColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) IconActive else IconInactive,
        label = "iconColor"
    )

    val iconAlpha = if (isPressed) 0.8f else 1f

    Box(
        modifier = Modifier
            .size(56.dp) // Fixed column width
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null // Custom ripple handled by shape if needed, but spec says specific ripple
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Circle Container
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(18.dp),
                tint = iconColor.copy(alpha = iconAlpha)
            )
        }
    }
}

data class BottomNavItem(
    val icon: ImageVector,
    val label: String // Kept for accessibility, though not shown
)
