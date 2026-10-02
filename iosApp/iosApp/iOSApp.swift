import SwiftUI
import iosAppHost

@main
struct IOSApp: App {
    init() {
        // The Koin graph and the Compose host live in the Kotlin composition
        // root (:iosAppHost), the iOS counterpart of androidApp's
        // OpenCodeMobileApp.onCreate. The Swift shell stays minimal (ADR 0003).
        IosConnectionCompositionRootKt.startIosKoin()

        // Acceptance-only seam (OPE-169): the simulator has no camera, so the
        // UI test supplies a QR payload through "-OPEQRPayload <value>" and we
        // feed it to the same controller path the scanner feeds. Debug only.
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let flag = arguments.firstIndex(of: "-OPEQRPayload"), flag + 1 < arguments.count {
            IosConnectionCompositionRootKt.submitScannedPayloadForAcceptance(payload: arguments[flag + 1])
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
