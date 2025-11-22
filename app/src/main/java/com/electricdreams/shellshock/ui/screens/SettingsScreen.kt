package com.electricdreams.shellshock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.electricdreams.shellshock.ui.components.CashAppPrimaryButton
import com.electricdreams.shellshock.ui.components.CashAppSecondaryButton
import com.electricdreams.shellshock.ui.components.CashAppCard
import com.electricdreams.shellshock.ui.components.CashAppTopBar
import com.electricdreams.shellshock.ui.theme.CashAppTypography
import com.electricdreams.shellshock.ui.theme.CashGreen
import com.electricdreams.shellshock.ui.theme.Gray400
import com.electricdreams.shellshock.ui.theme.Gray700

@Composable
fun SettingsScreen(
    currentCurrency: String,
    onCurrencySelected: (String) -> Unit,
    mints: List<String>,
    onAddMint: (String) -> Unit,
    onRemoveMint: (String) -> Unit,
    onResetMints: () -> Unit,
    itemsCount: Int,
    onImportItems: () -> Unit,
    onManageItems: () -> Unit,
    onClearItems: () -> Unit,
    onBackClick: (() -> Unit)? = null,
    onSaveClick: () -> Unit
) {
    Scaffold(
        topBar = {
            CashAppTopBar(
                title = "Settings",
                onBackClick = onBackClick,
                actions = {
                    TextButton(onClick = onSaveClick) {
                        Text("Save", color = CashGreen, fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        containerColor = Color.White
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Currency Section
            item {
                SettingsSection(title = "Currency") {
                    CurrencyOption(
                        label = "USD ($)",
                        selected = currentCurrency == "USD",
                        onClick = { onCurrencySelected("USD") }
                    )
                    CurrencyOption(
                        label = "EUR (€)",
                        selected = currentCurrency == "EUR",
                        onClick = { onCurrencySelected("EUR") }
                    )
                    CurrencyOption(
                        label = "GBP (£)",
                        selected = currentCurrency == "GBP",
                        onClick = { onCurrencySelected("GBP") }
                    )
                    CurrencyOption(
                        label = "JPY (¥)",
                        selected = currentCurrency == "JPY",
                        onClick = { onCurrencySelected("JPY") }
                    )
                }
            }

            // Mints Section
            item {
                SettingsSection(title = "Lightning Mints") {
                    var newMintUrl by remember { mutableStateOf("") }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newMintUrl,
                            onValueChange = { newMintUrl = it },
                            placeholder = { Text("Add new mint URL") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (newMintUrl.isNotBlank()) {
                                    onAddMint(newMintUrl)
                                    newMintUrl = ""
                                }
                            })
                        )
                        IconButton(onClick = {
                            if (newMintUrl.isNotBlank()) {
                                onAddMint(newMintUrl)
                                newMintUrl = ""
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Mint", tint = CashGreen)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    mints.forEach { mint ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = mint,
                                style = CashAppTypography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = Gray700
                            )
                            IconButton(onClick = { onRemoveMint(mint) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Gray400)
                            }
                        }
                        Divider(color = Color.LightGray.copy(alpha = 0.3f))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = onResetMints) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset to Defaults", color = Gray400)
                    }
                }
            }

            // Catalog Section
            item {
                SettingsSection(title = "Catalog") {
                    Text(
                        text = if (itemsCount == 0) "No items in catalog" else "$itemsCount items in catalog",
                        style = CashAppTypography.bodyMedium,
                        color = Gray700
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CashAppSecondaryButton(
                            text = "Import CSV",
                            onClick = onImportItems,
                            modifier = Modifier.weight(1f)
                        )
                        CashAppPrimaryButton(
                            text = "Manage",
                            onClick = onManageItems,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    if (itemsCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = onClearItems,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear All Items", color = Color.Red)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = CashAppTypography.titleMedium,
            color = Gray700,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        CashAppCard(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
fun CurrencyOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = CashGreen)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = CashAppTypography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
