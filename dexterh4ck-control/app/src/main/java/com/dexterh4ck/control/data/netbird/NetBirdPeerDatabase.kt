package com.dexterh4ck.control.data.netbird

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NetBirdPeerEntity::class], version = 1, exportSchema = false)
abstract class NetBirdPeerDatabase : RoomDatabase() {
    abstract fun peers(): NetBirdPeerDao

    companion object {
        @Volatile private var INSTANCE: NetBirdPeerDatabase? = null
        fun get(context: Context): NetBirdPeerDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NetBirdPeerDatabase::class.java,
                "netbird-peers.db"
            ).build().also { INSTANCE = it }
        }
    }
}
