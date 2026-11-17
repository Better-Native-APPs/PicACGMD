package app.picacgmd.core.data.settings

/** Non-secret configuration and last successful sync time. The password lives in a separate encrypted file. */
data class WebDavSettings(
    val address: String = "",
    val username: String = "",
    val folder: String = "PicACGMD",
    val autoSync: Boolean = false,
    val wifiOnly: Boolean = true,
    val lastSyncedAt: Long? = null,
) {
    override fun toString(): String = "WebDavSettings(autoSync=$autoSync, wifiOnly=$wifiOnly)"
}
