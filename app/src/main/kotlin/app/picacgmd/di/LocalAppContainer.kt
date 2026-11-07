package app.picacgmd.di

import androidx.compose.runtime.staticCompositionLocalOf
import app.picacgmd.core.domain.di.AppContainer

/** Application-scoped container, provided once at the root by MainActivity. Screens read it via `LocalAppContainer.current`. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided")
}
