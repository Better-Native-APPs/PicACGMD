package app.picacgmd.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Full-file snapshots; no application data is handed to an unprotected or unknown transport. */
class PicaBackupAgent : BackupAgent() {
    private var restoreStorage: SystemBackupStorage? = null
    private var restoreRefused = false

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor) = Unit

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) data.transportFlags else null
        backupToTransport(flags) { super.onFullBackup(data) }
    }

    internal fun backupToTransport(
        flags: Int?,
        storage: SystemBackupStorage = SystemBackupStorage(this),
        handOff: () -> Unit,
    ) {
        // Android 8 rejects requireFlags, then caches an empty allowlist that retries treat as whole domains.
        // ADB can bypass both XML rules; reject every unprotected or unknown transport before the platform pass.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || flags == null || !allowsTransport(flags)) return
        // Finish a pending restore outside this backup's cleanup scope. Failure leaves its inputs for startup.
        storage.prepareBackup(includeSecrets = true)
        try {
            handOff()
        } finally {
            storage.clearSnapshots()
        }
    }

    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long,
    ) {
        if (restoreRefused) {
            discardRestoreFile(data, size)
            return
        }
        val storage = try {
            restoreStorage ?: SystemBackupStorage(this).also {
                it.beginRestore()
                restoreStorage = it
            }
        } catch (_: Exception) {
            // A session missing even its first file cannot be retried or committed as a complete delivery.
            restoreRefused = true
            Log.w("PicaBackupAgent", "Restore session refused")
            discardRestoreFile(data, size)
            return
        }
        // The platform checks this original destination against the XML allowlist before writing it.
        super.onRestoreFile(data, size, destination, type, mode, mtime)
        if (type == TYPE_FILE) storage.receiveRestoredSnapshot(destination, size)
    }

    override fun onRestoreFinished() {
        try {
            // Only this session's validated deliveries may commit a pre-container restore.
            restoreStorage?.finishRestore()
        } finally {
            restoreStorage = null
            restoreRefused = false
        }
    }

    private fun discardRestoreFile(data: ParcelFileDescriptor, size: Long) {
        // Older platforms do not drain callback leftovers. Read exactly this file without closing the caller's fd.
        try {
            val input = FileInputStream(data.fileDescriptor)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count < 0) return
                remaining -= count
            }
        } catch (_: IOException) {
            Log.w("PicaBackupAgent", "Could not drain refused restore file")
        }
    }

    internal companion object {
        @RequiresApi(Build.VERSION_CODES.P)
        fun allowsTransport(flags: Int): Boolean =
            flags and (FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED or FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0
    }
}
