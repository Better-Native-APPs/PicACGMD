package app.picacgmd.core.domain.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.network.UserProfile
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserRepository(private val session: SessionManager, scope: CoroutineScope) {
    private val mutableProfile = MutableStateFlow<UserProfile?>(null)
    @Volatile private var profileEmail: String? = null
    val currentProfile: StateFlow<UserProfile?> = mutableProfile.asStateFlow()

    init {
        scope.launch {
            session.state.map { (it as? SessionState.SignedIn)?.email }.distinctUntilChanged().collect { email ->
                if (profileEmail != email) mutableProfile.value = null
            }
        }
    }

    suspend fun profile(): UserProfile {
        session.awaitReady()
        val email = (session.state.value as? SessionState.SignedIn)?.email
        val profile = session.withApi { it.userProfile() }
        if ((session.state.value as? SessionState.SignedIn)?.email == email && email != null) {
            profileEmail = email
            mutableProfile.value = profile
        }
        return profile
    }

    suspend fun updateSlogan(slogan: String): UserProfile {
        session.withApi { it.updateSlogan(slogan) }
        return profile()
    }

    suspend fun updatePassword(oldPassword: String, newPassword: String) {
        require(oldPassword.isNotEmpty() && newPassword.isNotEmpty()) { "密码不能为空" }
        session.withApi { it.updatePassword(oldPassword, newPassword) }
        session.updateStoredPassword(newPassword)
    }

    /** Decode at a sampled size, center-crop, and upload the protocol's 200×200 JPEG avatar. */
    suspend fun updateAvatar(imageBytes: ByteArray): UserProfile {
        val jpeg = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "头像图片无法读取" }
            val options = BitmapFactory.Options().apply {
                while (minOf(bounds.outWidth, bounds.outHeight) / (inSampleSize.coerceAtLeast(1) * 2) >= AVATAR_SIZE) {
                    inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                }
            }
            val source = requireNotNull(BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)) {
                "头像图片无法读取"
            }
            val avatar = Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.ARGB_8888)
            try {
                val side = minOf(source.width, source.height)
                val left = (source.width - side) / 2
                val top = (source.height - side) / 2
                Canvas(avatar).drawBitmap(
                    source, Rect(left, top, left + side, top + side), Rect(0, 0, AVATAR_SIZE, AVATAR_SIZE),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                ByteArrayOutputStream().use { output ->
                    check(avatar.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "头像图片编码失败" }
                    output.toByteArray()
                }
            } finally {
                source.recycle()
                avatar.recycle()
            }
        }
        session.withApi { it.updateAvatar(jpeg) }
        return profile()
    }

    private companion object {
        const val AVATAR_SIZE = 200
    }
}
