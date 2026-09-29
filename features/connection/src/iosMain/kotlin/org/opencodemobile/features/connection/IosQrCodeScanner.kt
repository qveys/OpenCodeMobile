@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.opencodemobile.features.connection

import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS [QrCodeScanner] over an AVFoundation `AVCaptureMetadataOutput` QR capture
 * session (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * The source set is `iosMain`, so the AVFoundation/UIKit dependency never
 * reaches `commonMain` or the Android target. [scan] resolves with the decoded
 * payload, or with `null` when the user cancels or denies camera access; the
 * text is returned verbatim because `ServerImportLink` remains the only parser.
 *
 * @param presenter supplies the view controller to present from. The
 *   composition root passes the Compose host controller; a null result resolves
 *   the scan immediately as a cancellation.
 */
public class IosQrCodeScanner(
    private val presenter: () -> UIViewController?,
) : QrCodeScanner {

    override suspend fun scan(): String? = suspendCancellableCoroutine { continuation ->
        val host = presenter()
        if (host == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val controller = QrScannerViewController { payload ->
            if (continuation.isActive) continuation.resume(payload)
        }
        // Full screen disables the interactive swipe-to-dismiss, so every exit
        // path goes through the capture surface's cancel control and always
        // resolves `scan()`.
        controller.modalPresentationStyle = UIModalPresentationFullScreen

        dispatch_async(dispatch_get_main_queue()) {
            host.presentViewController(controller, animated = true, completion = null)
        }

        continuation.invokeOnCancellation {
            dispatch_async(dispatch_get_main_queue()) {
                controller.dismissViewControllerAnimated(true, completion = null)
            }
        }
    }
}

/**
 * Minimal capture view: an `AVCaptureVideoPreviewLayer` plus a Cancel button.
 * It owns the session lifecycle and reports exactly one result.
 */
private class QrScannerViewController(
    private val onResult: (String?) -> Unit,
) : UIViewController(nibName = null, bundle = null),
    AVCaptureMetadataOutputObjectsDelegateProtocol {

    private val session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var handled = false

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor

        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> startCapture()

            AVAuthorizationStatusNotDetermined -> AVCaptureDevice.requestAccessForMediaType(
                AVMediaTypeVideo,
            ) { granted ->
                dispatch_async(dispatch_get_main_queue()) {
                    if (granted) startCapture() else complete(null)
                }
            }

            else -> complete(null)
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private fun startCapture() {
        session.sessionPreset = AVCaptureSessionPresetHigh

        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        if (device == null) {
            complete(null)
            return
        }
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
        if (input == null || !session.canAddInput(input)) {
            complete(null)
            return
        }
        session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) {
            complete(null)
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)

        val preview = AVCaptureVideoPreviewLayer(session = session)
        preview.videoGravity = AVLayerVideoGravityResizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        previewLayer = preview

        installCancelButton()
        session.startRunning()
    }

    private fun installCancelButton() {
        val button = UIButton.buttonWithType(UIButtonTypeSystem)
        button.setTitle("Cancel", forState = UIControlStateNormal)
        button.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        button.setFrame(CGRectMake(16.0, 48.0, 120.0, 44.0))
        button.addTarget(
            target = this,
            action = NSSelectorFromString("cancelTapped"),
            forControlEvents = UIControlEventTouchUpInside,
        )
        view.addSubview(button)
    }

    @ObjCAction
    fun cancelTapped() {
        complete(null)
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val value = (didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject)
            ?.stringValue
        if (value != null) {
            dispatch_async(dispatch_get_main_queue()) { complete(value) }
        }
    }

    private fun complete(payload: String?) {
        if (handled) return
        handled = true
        if (session.running) session.stopRunning()
        // Leave the full-screen capture surface before handing the result back,
        // so both the scanned and cancelled paths return to the entry/review
        // screen behind it. Dismissal is not awaited: the result must resolve
        // even if the controller was never fully presented (e.g. permission
        // denied in viewDidLoad).
        if (presentingViewController != null) {
            dismissViewControllerAnimated(true, completion = null)
        }
        onResult(payload)
    }
}
