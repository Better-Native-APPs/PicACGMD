package app.picacgmd.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ViewHistory::class,
        DownloadComic::class,
        DownloadEp::class,
        DownloadPicture::class,
        SearchHistory::class,
        ApiCache::class,
        RemoteImage::class,
        TagFavourite::class,
        Subscription::class,
    ],
    version = 4,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = DropObsoleteFoldersSpec::class),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = DropLocalLibrarySpec::class),
    ],
    exportSchema = true,
)
@TypeConverters(DownloadStatusConverters::class)
abstract class PikapikaDatabase : RoomDatabase() {
    abstract fun viewHistoryDao(): ViewHistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun apiCacheDao(): ApiCacheDao
    abstract fun remoteImageDao(): RemoteImageDao
    abstract fun tagFavouriteDao(): TagFavouriteDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        const val DATABASE_NAME: String = "pikapika.db"

        /** Keep one instance for the application lifetime. There is no destructive migration fallback. */
        fun create(context: Context): PikapikaDatabase =
            Room.databaseBuilder(context.applicationContext, PikapikaDatabase::class.java, DATABASE_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
