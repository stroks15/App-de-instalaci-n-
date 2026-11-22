package com.dexterh4ck.control.data.netbird

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NetBirdPeerDao {
    @Query("SELECT * FROM netbird_peers ORDER BY alias COLLATE NOCASE")
    fun observeAll(): Flow<List<NetBirdPeerEntity>>

    @Query("SELECT * FROM netbird_peers WHERE hardwareId = :hardwareId LIMIT 1")
    suspend fun findByHardwareId(hardwareId: String): NetBirdPeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: NetBirdPeerEntity)

    @Query("DELETE FROM netbird_peers WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}
