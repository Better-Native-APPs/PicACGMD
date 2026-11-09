package app.picacgmd.lock

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi

/** Uses system authentication only; the app never receives or stores a lock-screen password. */
internal class AppLockAuthenticator(
    private val activity: ComponentActivity,
    private val onUnlocked: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val keyguard = activity.getSystemService(KeyguardManager::class.java)
    private var biometricCancellation: CancellationSignal? = null
    var isAuthenticating: Boolean = false
        private set

    private val credentials = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isAuthenticating = false
        if (result.resultCode == Activity.RESULT_OK) onUnlocked()
        else onError("验证已取消，点击解锁重试")
    }

    val isDeviceSecure: Boolean get() = keyguard?.isDeviceSecure == true

    fun authenticate(allowBiometric: Boolean) {
        if (isAuthenticating) return
        if (!isDeviceSecure) {
            onUnlocked()
            return
        }
        isAuthenticating = true
        if (allowBiometric && Build.VERSION.SDK_INT >= 28) authenticateBiometric()
        else authenticateCredentials()
    }

    @RequiresApi(28)
    private fun authenticateBiometric() {
        val cancellation = CancellationSignal()
        biometricCancellation = cancellation
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("解锁哔咔漫画")
            .setSubtitle("验证身份后继续使用")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setNegativeButton("锁屏密码", activity.mainExecutor) { _, _ ->
                if (biometricCancellation === cancellation) {
                    biometricCancellation = null
                    authenticateCredentials()
                }
            }
        }
        builder.build().authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (biometricCancellation !== cancellation) return
                biometricCancellation = null
                isAuthenticating = false
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (biometricCancellation !== cancellation) return
                biometricCancellation = null
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                ) {
                    isAuthenticating = false
                    onError("验证已取消，点击解锁重试")
                } else {
                    // Missing/enrollment-disabled/locked-out biometrics must still allow the password.
                    authenticateCredentials()
                }
            }
        })
    }

    @Suppress("DEPRECATION") // The credential-only route is needed on API 26–29 and with biometrics disabled.
    private fun authenticateCredentials() {
        val intent = keyguard?.createConfirmDeviceCredentialIntent("解锁哔咔漫画", "请输入系统锁屏密码")
        if (intent != null) {
            credentials.launch(intent)
        } else {
            isAuthenticating = false
            if (!isDeviceSecure) onUnlocked()
            else onError("无法打开系统身份验证，请重试")
        }
    }

    fun cancel() {
        val cancellation = biometricCancellation
        biometricCancellation = null
        cancellation?.cancel()
    }
}
