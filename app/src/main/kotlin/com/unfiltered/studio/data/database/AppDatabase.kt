package com.unfiltered.studio.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.unfiltered.studio.data.database.dao.CatalogDao
import com.unfiltered.studio.data.database.dao.DownloadDao
import com.unfiltered.studio.data.database.dao.GenerationDao
import com.unfiltered.studio.data.database.dao.LoraDao
import com.unfiltered.studio.data.database.dao.PromptDao
import com.unfiltered.studio.data.database.dao.ProviderDao
import com.unfiltered.studio.data.database.entities.ActiveLoraEntity
import com.unfiltered.studio.data.database.entities.CatalogCacheEntity
import com.unfiltered.studio.data.database.entities.DownloadEntity
import com.unfiltered.studio.data.database.entities.GenerationEntity
import com.unfiltered.studio.data.database.entities.GenerationLoraEntity
import com.unfiltered.studio.data.database.entities.ImageEntity
import com.unfiltered.studio.data.database.entities.LoraCategoryEntity
import com.unfiltered.studio.data.database.entities.LoraEntity
import com.unfiltered.studio.data.database.entities.LoraLinkEntity
import com.unfiltered.studio.data.database.entities.PromptEntity
import com.unfiltered.studio.data.database.entities.PromptTemplateEntity
import com.unfiltered.studio.data.database.entities.ProviderEntity

/**
 * Schema is exported to app/schemas; bump [version] and add a Migration for every schema change
 * (no destructive fallback — users' history must survive upgrades).
 */
@Database(
    entities = [
        ProviderEntity::class,
        GenerationEntity::class,
        ImageEntity::class,
        GenerationLoraEntity::class,
        LoraEntity::class,
        LoraLinkEntity::class,
        LoraCategoryEntity::class,
        ActiveLoraEntity::class,
        PromptEntity::class,
        PromptTemplateEntity::class,
        DownloadEntity::class,
        CatalogCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun generationDao(): GenerationDao
    abstract fun loraDao(): LoraDao
    abstract fun promptDao(): PromptDao
    abstract fun downloadDao(): DownloadDao
    abstract fun catalogDao(): CatalogDao

    companion object {
        const val NAME = "unfiltered_studio.db"
    }
}
