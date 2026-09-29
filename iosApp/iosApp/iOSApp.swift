import SwiftUI
import iosAppHost

@main
struct IOSApp: App {
    init() {
        // The Koin graph and the Compose host live in the Kotlin composition
        // root (:iosAppHost), the iOS counterpart of androidApp's
        // OpenCodeMobileApp.onCreate. The Swift shell stays minimal (ADR 0003).
        IosConnectionCompositionRootKt.startIosKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
