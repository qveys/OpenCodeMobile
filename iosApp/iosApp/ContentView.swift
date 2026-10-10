import SwiftUI
import UIKit
import iosAppHost

/// Bridges the Compose connection screen into the SwiftUI scene.
///
/// The Kotlin composition root (`:iosAppHost`) owns Koin and the Compose UI, so
/// Swift only asks it for the hosted `UIViewController`. That controller builds
/// `IosQrCodeScanner` from itself (via Compose Multiplatform's
/// `LocalUIViewController`) and passes it to `ConnectionSetupScreen`, matching
/// `androidApp`'s `MainActivity` + `ConnectionCompositionRoot` split.
struct ComposeConnectionView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // UI tests pass -OPEUIAccessibility so XCUITest can read the Compose
        // semantics tree; production keeps the default (VoiceOver-triggered)
        // accessibility sync. Debug builds only.
        var accessibilitySyncAlways = false
        #if DEBUG
        accessibilitySyncAlways = ProcessInfo.processInfo.arguments.contains("-OPEUIAccessibility")
        #endif
        return IosConnectionCompositionRootKt.connectionSetupViewController(
            accessibilitySyncAlways: accessibilitySyncAlways
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase

    /// §7.3: when the scene leaves `.active`, an opaque cover is drawn over the
    /// whole hierarchy so the OS app-switcher snapshot never contains the
    /// transcript. It is on by default; the user preference is read from the
    /// shared Kotlin store, the same one `androidApp` consults.
    @State private var privacyCoverShown = false

    var body: some View {
        ComposeConnectionView()
            // The Compose UI draws behind the home indicator and status bar;
            // the CMP host (IosConnectionCompositionRoot) applies the insets
            // with safeDrawingPadding().
            .ignoresSafeArea()
            .overlay {
                if privacyCoverShown {
                    PrivacyCoverView()
                }
            }
            .onChange(of: scenePhase) { phase in
                switch phase {
                case .active:
                    privacyCoverShown = false
                case .inactive, .background:
                    privacyCoverShown =
                        IosConnectionCompositionRootKt.multitaskMaskingEnabled()
                @unknown default:
                    privacyCoverShown =
                        IosConnectionCompositionRootKt.multitaskMaskingEnabled()
                }
            }
    }
}

/// The opaque app-switcher cover. Deliberately content-free: it must reveal
/// nothing about the session, only that the app is protected.
private struct PrivacyCoverView: View {
    var body: some View {
        ZStack {
            Color.black
            Image(systemName: "lock.fill")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
        }
        .ignoresSafeArea()
    }
}
