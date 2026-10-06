import Foundation
import sharedApplication
import sharedData
import sharedDomain

/// The iOS composition root for the D8 cache write path (OPE-180).
///
/// It builds the cache stack in Kotlin with `createIosCacheStack()` and starts the
/// `CacheWritePipeline` with `stack.writer()`, the only writer the app may use (it
/// is already wrapped in the connectivity gate). The pipeline binds the realtime
/// `EventSource` to that gate and projects server snapshots and events into the
/// cache, so the cache is written only from the realtime pipeline.
///
/// The Swift host calls `start(source:serverId:projectId:)` once the connection
/// composition root has built the realtime pipeline, exactly as the app shell
/// starts the permission surface. Until then the stack opens read-only (the D8
/// default), which is the fail-close direction.
final class CacheComposition {
    /// The one cache stack of the app: OS Data Protection, no app passphrase.
    private let stack: CacheStack =
        IosCacheStackKt.createIosCacheStack(initialConnectionState: .offline)

    private var started = false

    /// Starts the write path for one connection. Idempotent.
    func start(source: EventSource, serverId: String, projectId: String) {
        guard !started else { return }
        started = true

        // `writer()` is suspend, so Kotlin exports it with a completion handler.
        // The stack hands out only the D8-gated writer; there is no ungated one.
        stack.writer { writer, error in
            guard let writer, error == nil else {
                // The cache is disposable: an open failure leaves the write path off
                // rather than leaking an ungated writer.
                return
            }
            IosCacheWriteKt.startIosCacheWritePipeline(
                source: source,
                writer: writer,
                gate: self.stack.gate,
                serverId: serverId,
                projectId: projectId
            )
        }
    }
}
