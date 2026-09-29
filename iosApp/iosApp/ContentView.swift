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
        IosConnectionCompositionRootKt.connectionSetupViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeConnectionView()
            // The Compose UI draws behind the home indicator and status bar;
            // top/bottom insets are applied by the CMP screen itself.
            .ignoresSafeArea()
    }
}
