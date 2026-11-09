package app.picacgmd.core.designsystem.window

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy

/** Separate Compose windows must observe the setting even while they are already open. */
val LocalSecureWindows = compositionLocalOf { false }

@Composable
fun secureWindowPolicy(): SecureFlagPolicy =
    if (LocalSecureWindows.current) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit

@Composable
fun secureDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
    decorFitsSystemWindows: Boolean = true,
): DialogProperties = DialogProperties(
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    securePolicy = secureWindowPolicy(),
    usePlatformDefaultWidth = usePlatformDefaultWidth,
    decorFitsSystemWindows = decorFitsSystemWindows,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun secureModalBottomSheetProperties(
    shouldDismissOnBackPress: Boolean = true,
    shouldDismissOnClickOutside: Boolean = true,
): ModalBottomSheetProperties = ModalBottomSheetProperties(
    securePolicy = secureWindowPolicy(),
    shouldDismissOnBackPress = shouldDismissOnBackPress,
    shouldDismissOnClickOutside = shouldDismissOnClickOutside,
)

@Composable
fun securePopupProperties(
    focusable: Boolean = false,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    clippingEnabled: Boolean = true,
    excludeFromSystemGesture: Boolean = true,
    usePlatformDefaultWidth: Boolean = false,
): PopupProperties = PopupProperties(
    focusable = focusable,
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    securePolicy = secureWindowPolicy(),
    clippingEnabled = clippingEnabled,
    excludeFromSystemGesture = excludeFromSystemGesture,
    usePlatformDefaultWidth = usePlatformDefaultWidth,
)
