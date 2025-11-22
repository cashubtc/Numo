package com.electricdreams.shellshock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry
import com.electricdreams.shellshock.ui.components.CashAppCard
import com.electricdreams.shellshock.ui.components.CashAppTopBar
import com.electricdreams.shellshock.ui.theme.CashAppTypography
import com.electricdreams.shellshock.ui.theme.Gray400
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun HistoryScreen(
    history: List<PaymentHistoryEntry>,
    onBackClick: (() -> Unit)? = null,
    onClearHistoryClick: () -> Unit,
    onCopyClick: (String) -> Unit,
    onOpenClick: (String) -> Unit,
    onDeleteClick: (PaymentHistoryEntry) -> Unit
) {
    Scaffold(
        topBar = {
            CashAppTopBar(
                title = "Activity",
                onBackClick = onBackClick,
                actions = {
                    if (history.isNotEmpty()) {
                        TextButton(onClick = onClearHistoryClick) {
                            Text("Clear", color = Color.Red)
                        }
                    }
                }
            )
        },
        containerColor = Color.White
    ) { paddingValues ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No recent activity",
                    style = CashAppTypography.bodyLarge,
                    color = Gray400
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(history) { entry ->
                    HistoryItem(
                        entry = entry,
                        onCopyClick = { onCopyClick(entry.token) },
                        onOpenClick = { onOpenClick(entry.token) },
                        onDeleteClick = { onDeleteClick(entry) }
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItem(
    entry: PaymentHistoryEntry,
    onCopyClick: () -> Unit,
    onOpenClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

    CashAppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = null // Card itself isn't clickable, actions are buttons
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${entry.amount} ₿",
                    style = CashAppTypography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = dateFormat.format(entry.date),
                    style = CashAppTypography.bodySmall,
                    color = Gray400
                )
            }

            Row {
                IconButton(onClick = onCopyClick) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = Gray400
                    )
                }
                IconButton(onClick = onOpenClick) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open",
                        tint = Gray400
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Gray400
                    )
                }
            }
        }
    }
}
