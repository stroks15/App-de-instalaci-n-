package com.dexterh4ck.control.data.netbird

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "netbird_peers", indices = [Index(value = ["hardwareId"], unique = true), Index(value = ["netbirdIp"], unique = true)])
data class NetBirdPeerEntity(
    @PrimaryKey val peerId: String,
    val hardwareId: String,
    val alias: String,
    val netbirdIp: String,
    val status: String,
    val lastSeenEpochMs: Long
)
