package org.opencodemobile.features.catalog

// V1-09 model/agent surface: the screen that lists exactly what the server
// exposes via `GET /provider` and `GET /agent`, its empty-state copy
// (`EMPTY_MESSAGE` vs `UNSUPPORTED_MESSAGE`) and its transient-error copy. There
// is deliberately no built-in catalog. All state and the error/empty distinction
// live in `org.opencodemobile.shared.application.interaction.ServerCatalogController`;
// this module only presents and forwards the refresh intent.
//
// Must not depend on shared/networking, shared/persistence, shared/security, or
// another feature's internals (§5.2) -- only shared/domain, shared/application,
// and design-system.
public object CatalogModule
