package com.electricdreams.shellshock.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.ui.components.CashAppCard
import com.electricdreams.shellshock.ui.components.CashAppPrimaryButton
import com.electricdreams.shellshock.ui.components.CashAppTopBar
import com.electricdreams.shellshock.ui.theme.CashAppTypography
import com.electricdreams.shellshock.ui.theme.CashGreen
import com.electricdreams.shellshock.ui.theme.Gray400
import com.electricdreams.shellshock.ui.theme.Gray700
import java.io.File

@Composable
fun CatalogScreen(
    items: List<Item>,
    basketQuantities: Map<String, Int>,
    basketTotalItems: Int,
    basketTotalPrice: String,
    onItemAdd: (Item) -> Unit,
    onItemRemove: (Item) -> Unit,
    onViewBasket: () -> Unit,
    onCheckout: () -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            CashAppTopBar(
                title = "Catalog",
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            if (basketTotalItems > 0) {
                BasketSummaryBar(
                    count = basketTotalItems,
                    total = basketTotalPrice,
                    onViewBasket = onViewBasket,
                    onCheckout = onCheckout
                )
            }
        },
        containerColor = Color.White
    ) { paddingValues ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No items in catalog",
                    style = CashAppTypography.bodyLarge,
                    color = Gray400
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items) { item ->
                    CatalogItem(
                        item = item,
                        quantity = basketQuantities[item.id] ?: 0,
                        onAdd = { onItemAdd(item) },
                        onRemove = { onItemRemove(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun CatalogItem(
    item: Item,
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit
) {
    CashAppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onAdd // Tapping card adds item
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Image
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.LightGray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                val imageBitmap = remember(item.imagePath) {
                    if (!item.imagePath.isNullOrEmpty()) {
                        val file = File(item.imagePath)
                        if (file.exists()) {
                            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                        } else null
                    } else null
                }

                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ShoppingCart, // Placeholder
                        contentDescription = null,
                        tint = Gray400,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info
            Text(
                text = item.name,
                style = CashAppTypography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            if (!item.variationName.isNullOrEmpty()) {
                Text(
                    text = item.variationName,
                    style = CashAppTypography.bodySmall,
                    color = Gray400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$${String.format("%.2f", item.price)}", // Assuming USD for display logic simplicity, but should pass formatted string
                style = CashAppTypography.bodyMedium,
                color = CashGreen,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Controls
            if (quantity > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.LightGray.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                    }
                    
                    Text(
                        text = quantity.toString(),
                        style = CashAppTypography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    IconButton(
                        onClick = onAdd,
                        modifier = Modifier
                            .size(32.dp)
                            .background(CashGreen, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            } else {
                CashAppPrimaryButton(
                    text = "Add",
                    onClick = onAdd,
                    modifier = Modifier.height(36.dp),
                    // Need to adjust button style for small size or create a small button variant
                )
            }
        }
    }
}

@Composable
fun BasketSummaryBar(
    count: Int,
    total: String,
    onViewBasket: () -> Unit,
    onCheckout: () -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$count items",
                    style = CashAppTypography.bodySmall,
                    color = Gray700
                )
                Text(
                    text = total,
                    style = CashAppTypography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            CashAppPrimaryButton(
                text = "Checkout",
                onClick = onCheckout,
                modifier = Modifier.width(120.dp)
            )
        }
    }
}
