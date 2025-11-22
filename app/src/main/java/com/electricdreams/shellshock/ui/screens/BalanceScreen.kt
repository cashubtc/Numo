package com.electricdreams.shellshock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.electricdreams.shellshock.ui.components.CashAppTopBar
import com.electricdreams.shellshock.ui.theme.CashAppTypography
import com.electricdreams.shellshock.ui.theme.CashGreen
import com.electricdreams.shellshock.ui.theme.Gray400
import com.electricdreams.shellshock.ui.theme.Gray700

@Composable
fun BalanceScreen(
    balance: Long?,
    statusMessage: String,
    onBackClick: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            CashAppTopBar(
                title = "Check Balance",
                onBackClick = onBackClick
            )
        },
        containerColor = Color.White
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Nfc,
                contentDescription = "NFC",
                modifier = Modifier.size(64.dp),
                tint = CashGreen
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (balance != null) {
                Text(
                    text = "$balance ₿",
                    style = CashAppTypography.displayLarge.copy(fontSize = 48.sp),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Current Balance",
                    style = CashAppTypography.bodyLarge,
                    color = Gray400
                )
            } else {
                Text(
                    text = "Tap Card",
                    style = CashAppTypography.headlineMedium,
                    color = Gray700,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = statusMessage,
                style = CashAppTypography.bodyMedium,
                color = Gray700,
                textAlign = TextAlign.Center
            )
        }
    }
}
