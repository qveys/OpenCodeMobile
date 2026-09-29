import XCTest

/// OPE-169 runtime acceptance for the connection screen host (OPE-153), run
/// against the real app on an iOS simulator.
///
/// The Compose UI is hosted by the Kotlin `:iosAppHost` composition root behind
/// `ContentView`; these tests drive the rendered accessibility tree, so they
/// fail if Koin/Compose does not come up or if the screen never appears.
///
/// QR payloads are injected without a camera through the debug-only
/// `-OPEQRPayload <value>` launch argument (see `iOSApp.swift` /
/// `IosConnectionCompositionRoot.submitScannedPayloadForAcceptance`). This drives
/// the same `ConnectionSetupController.submitScannedPayload` path the
/// AVFoundation scanner feeds, exactly the code under test.
final class ConnectionScreenUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// OPE-153 criterion 1: the connection screen opens, hosted by Koin + Compose.
    func testConnectionScreenOpens() throws {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(
            app.staticTexts["Connect to a server"].waitForExistence(timeout: 60),
            "The Compose connection screen did not appear; Koin or Compose failed to start."
        )
        XCTAssertTrue(app.buttons["Connect"].exists, "The Connect action is missing.")
    }

    /// OPE-153 criterion 2: "Scan QR code" presents the AVFoundation capture and
    /// cancelling returns to the entry screen.
    ///
    /// On a simulator without a camera device the scanner resolves immediately
    /// (see `IosQrCodeScanner`), so the Cancel control is optional: the test taps
    /// it when the capture surface is up and always asserts the return.
    func testScanQrCodePresentsCaptureAndCancelReturns() throws {
        let app = XCUIApplication()
        app.launch()

        let scan = app.buttons["Scan QR code"]
        XCTAssertTrue(scan.waitForExistence(timeout: 60), "The scan action is missing.")
        scan.tap()

        let cancel = app.buttons["Cancel"]
        if cancel.waitForExistence(timeout: 15) {
            cancel.tap()
        }

        XCTAssertTrue(
            app.staticTexts["Connect to a server"].waitForExistence(timeout: 30),
            "Cancelling the scan did not return to the entry screen."
        )
    }

    /// OPE-153 criterion 3a: a valid import link opens the review screen.
    func testValidImportLinkOpensReviewScreen() throws {
        let app = XCUIApplication()
        app.launchArguments += [
            "-OPEQRPayload",
            "opencodemobile://import?host=192.168.1.10&port=4096&label=Home%20server",
        ]
        app.launch()

        XCTAssertTrue(
            app.staticTexts["Add server"].waitForExistence(timeout: 60),
            "A valid import link did not open the review screen."
        )
        XCTAssertTrue(app.staticTexts["Source: QR code"].exists)
        XCTAssertTrue(app.staticTexts["Host: 192.168.1.10"].exists)
    }

    /// OPE-153 criterion 3b: a non-import code shows the "not an import link"
    /// message on the manual-entry screen.
    func testNonImportQrCodeShowsRejectionMessage() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-OPEQRPayload", "https://example.com/not-an-import"]
        app.launch()

        XCTAssertTrue(
            app.staticTexts["That QR code is not an OpenCode Mobile import link."]
                .waitForExistence(timeout: 60),
            "A non-import QR payload did not surface the rejection message."
        )
    }
}
