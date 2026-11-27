package dev.tg9.tg

import java.util.Locale
import org.drinkless.tdlib.TdApi

/** TDLib's file cache: every photo, video, voice message and file opened stays in
 *  files/tdlib/files until something deletes it, and a keypad phone has little storage to spare.
 *
 *  TDLib's own storage optimizer does the deleting: once a day it drops what hasn't been opened
 *  for [MAX_AGE_DAYS] days, then the least recently opened until the rest fits in [MAX_CACHE_MB].
 *  Profile photos, stickers, thumbnails and wallpapers stay (TDLib's defaults), and so does
 *  anything opened within the last hour. A deleted file just downloads again when it's opened.
 *  Settings → Storage shows the size and empties it ([clearAll]). */
object Storage {
    const val MAX_CACHE_MB = 200L
    const val MAX_AGE_DAYS = 3

    /** Sent once TDLib is ready (see [Tg]); TDLib keeps them in its database. */
    fun options(): List<TdApi.SetOption> = listOf(
        TdApi.SetOption("use_storage_optimizer", TdApi.OptionValueBoolean(true)),
        // In kilobytes.
        TdApi.SetOption("storage_max_files_size", TdApi.OptionValueInteger(MAX_CACHE_MB * 1024)),
        // In seconds.
        TdApi.SetOption("storage_max_time_from_last_access", TdApi.OptionValueInteger(MAX_AGE_DAYS * 24 * 60 * 60L)),
    )

    /** Deletes every cached file it may: no size, age or count is kept, and nothing is immune for
     *  being recent. TDLib's default file types still apply, so avatars and stickers stay. */
    fun clearAll(): TdApi.OptimizeStorage = TdApi.OptimizeStorage(0, 0, 0, 0, null, null, null, false, 0)

    /** "84 MB in 312 files", from TDLib's quick estimate. */
    fun label(stats: TdApi.StorageStatisticsFast): String {
        val files = if (stats.fileCount == 1) "1 file" else "${stats.fileCount} files"
        return "${size(stats.filesSize)} in $files"
    }

    fun size(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
            mb < 10 -> String.format(Locale.US, "%.1f MB", mb)
            else -> "${mb.toLong()} MB"
        }
    }
}
