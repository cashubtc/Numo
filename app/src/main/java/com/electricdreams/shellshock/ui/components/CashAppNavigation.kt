package com.electricdreams.shellshock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.electricdreams.shellshock.ui.theme.CashAppTypography
import com.electricdreams.shellshock.ui.theme.CashGreen
import com.electricdreams.shellshock.ui.theme.Gray400
import com.electricdreams.shellshock.ui.theme.Gray700
import com.electricdreams.shellshock.ui.theme.White

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
            IconButton(onClick = {}, enabled = false) { }
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
                IconButton(onClick = {}, enabled = false) { }
            }
        }
    }
}

@Composable
fun CashAppBottomBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = White,
        contentColor = Gray400,
        tonalElevation = 8.dp
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onItemSelected(index) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = if (item.label != null) {
                    { Text(text = item.label, style = CashAppTypography.bodySmall) }
                } else null,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = CashGreen,
                    selectedTextColor = CashGreen,
                    unselectedIconColor = Gray400,
                    unselectedTextColor = Gray400,
                    indicatorColor = Color.Transparent // No pill indicator behind icon
                )
            )
        }
    }
}

data class BottomNavItem(
    val icon: ImageVector,
    val label: String? = null
)
