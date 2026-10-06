import XCTest

/// OPE-169 runtime acceptance for the connection screen host (OPE-153), run
/// against the real app on an iOS simulator.
///
/// The Compose UI is hosted by the Kotlin `:iosAppHost` composition root behind
/// `ContentView`; these tests drive the rendered accessibility tree, so they
/// fail if Koin/Compose does not come up or if the screen never appears.
///
/// Launch arguments:
/// - `-OPEUIAccessibility` makes `:iosAppHost` build the Compose semantics tree
///   even though XCUITest is not a VoiceOver client (see `ContentView.swift`).
/// - `-OPEQRPayload <value>` injects a QR payload without a camera (see
///   `iOSApp.swift`), driving the same
///   `ConnectionSetupController.submitScannedPayload` path the AVFoundation
///   scanner feeds.
final class ConnectionScreenUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Launches the app with accessibility sync enabled and any extra arguments.
    @discardableResult
    private func launchApp(extraArguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OPEUIAccessibility"] + extraArguments
        app.launch()
        return app
    }

    /// Matches an element by accessibility label or identifier, whatever its
    /// element type (Compose may expose a text/button as different AX classes).
    private func element(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        let predicate = NSPredicate(format: "label == %@ OR identifier == %@", text, text)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    /// OPE-153 criterion 1: the connection screen opens, hosted by Koin + Compose.
    func testConnectionScreenOpens() throws {
        let app = launchApp()

        XCTAssertTrue(
            element(app, "Connect to a server").waitForExistence(timeout: 60),
            "The Compose connection screen did not appear; Koin or Compose failed to start."
        )
        XCTAssertTrue(element(app, "Connect").exists, "The Connect action is missing.")
    }

    /// OPE-153 criterion 2: "Scan QR code" presents the AVFoundation capture and
    /// cancelling returns to the entry screen.
    ///
    /// On a simulator without a camera device the scanner resolves immediately
    /// (see `IosQrCodeScanner`), so the Cancel control is optional: the test taps
    /// it when the capture surface is up and always asserts the return.
    func testScanQrCodePresentsCaptureAndCancelReturns() throws {
        let app = launchApp()

        let scan = element(app, "Scan QR code")
        XCTAssertTrue(scan.waitForExistence(timeout: 60), "The scan action is missing.")
        scan.tap()

        let cancel = element(app, "Cancel")
        if cancel.waitForExistence(timeout: 15) {
            cancel.tap()
        } else {
            print("SCAN_TREE_NO_CANCEL:\n\(app.debugDescription)")
        }

        let returned = element(app, "Connect to a server").waitForExistence(timeout: 30)
        if !returned {
            print("SCAN_TREE_NOT_RETURNED:\n\(app.debugDescription)")
        }
        XCTAssertTrue(returned, "Cancelling the scan did not return to the entry screen.")
    }

    /// OPE-153 criterion 3a: a valid import link opens the review screen.
    func testValidImportLinkOpensReviewScreen() throws {
        let app = launchApp(extraArguments: [
            "-OPEQRPayload",
            "opencodemobile://import?host=192.168.1.10&port=4096&label=Home%20server",
        ])

        XCTAssertTrue(
            element(app, "Add server").waitForExistence(timeout: 60),
            "A valid import link did not open the review screen."
        )
        XCTAssertTrue(element(app, "Source: QR code").exists)
        XCTAssertTrue(element(app, "Host: 192.168.1.10").exists)
    }

    /// OPE-153 criterion 3b: a non-import code shows the "not an import link"
    /// message on the manual-entry screen.
    func testNonImportQrCodeShowsRejectionMessage() throws {
        let app = launchApp(extraArguments: ["-OPEQRPayload", "https://example.com/not-an-import"])

        XCTAssertTrue(
            element(app, "That QR code is not an OpenCode Mobile import link.")
                .waitForExistence(timeout: 60),
            "A non-import QR payload did not surface the rejection message."
        )
    }

    /// OPE-274 F2: the local-access settings screen is reachable from the app,
    /// and its platform-dependent controls are correct. On iOS, screen-capture
    /// blocking is not available, so that control must not be shown; the
    /// app-switcher masking control must be.
    func testSettingsEntryOpensLocalAccess() throws {
        let app = launchApp()

        let settings = element(app, "Settings")
        XCTAssertTrue(settings.waitForExistence(timeout: 60), "The Settings action is missing.")
        settings.tap()

        XCTAssertTrue(
            element(app, "Local access").waitForExistence(timeout: 30),
            "The local-access settings screen did not open."
        )
        XCTAssertTrue(
            element(app, "Hide content in the app switcher").exists,
            "The multitask-masking control must be offered on iOS."
        )
        XCTAssertFalse(
            element(app, "Block screenshots and screen recording").exists,
            "iOS cannot block captures; the control must not be offered."
        )

        element(app, "Back").tap()
        XCTAssertTrue(
            element(app, "Connect to a server").waitForExistence(timeout: 30),
            "Back did not return to the connection flow."
        )
    }
}
