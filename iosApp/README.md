# FermApp for iOS

A SwiftUI host for the Compose Multiplatform UI in `composeApp`. It needs a Mac with Xcode 16 or newer.

1. Build the framework: `./gradlew :composeApp:assembleComposeAppDebugXCFramework`.
2. In Xcode, create an iOS App project named `iosApp` in this folder, replace its sources with `iosApp/iOSApp.swift` and `iosApp/Info.plist`, and add `composeApp/build/XCFrameworks/debug/ComposeApp.xcframework` under Frameworks, Libraries and Embedded Content.
3. Set `FermAppApiUrl` in `Info.plist` to the API address the phone can reach.
