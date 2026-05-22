#!/bin/bash
sed -i 's/whenever(mockContext.getString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn("Numo POS payment of 100 sats")/Mockito.`when`(mockContext.getString(Mockito.anyInt(), Mockito.any())).thenReturn("Numo POS payment of 100 sats")/g' app/src/test/java/com/electricdreams/numo/payment/LightningMintHandlerTest.kt
