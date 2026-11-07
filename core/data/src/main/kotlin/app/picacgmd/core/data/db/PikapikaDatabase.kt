package app.picacgmd.core.data.db

import android.content.Context
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
        LocalFavouriteFolder::class,
        LocalFavourite::class,
        SearchHistory::class,
        ApiCache::class,
        RemoteImage::class,
        TagFavourite::class,
        Subscription::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DownloadStatusConverters::class)
abstract class PikapikaDatabase : RoomDatabase() {
    abstract fun viewHistoryDao(): ViewHistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun localFavouriteDao(): LocalFavouriteDao
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
