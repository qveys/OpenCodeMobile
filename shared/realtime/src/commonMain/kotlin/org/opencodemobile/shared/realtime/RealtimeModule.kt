package org.opencodemobile.shared.realtime

// Central EventProcessor per connection: SSE + polling fallback, backoff, snapshot/reconciliation (§6.2).
public object RealtimeModule
