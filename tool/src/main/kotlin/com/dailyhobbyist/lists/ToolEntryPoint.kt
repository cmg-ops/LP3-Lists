package com.dailyhobbyist.lists

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        // No server communication needed — Lists is fully offline.
    }

    override suspend fun onPushNotification(
        data: ByteArray,
    ) {
        // No push notifications.
    }
}
