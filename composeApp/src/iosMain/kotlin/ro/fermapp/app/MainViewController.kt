package ro.fermapp.app

import androidx.compose.ui.window.ComposeUIViewController

/** Entry point called from the SwiftUI app in iosApp/. */
@Suppress("FunctionName", "unused")
fun MainViewController() = ComposeUIViewController { App() }
