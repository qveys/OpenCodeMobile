package org.opencodemobile.features.composer

// Prompt composer; owns the dictation expect/actual (Cahier des charges v1.0, roadmap note). Must not depend on shared/networking, shared/persistence, shared/security, or another feature's internals (§5.2) -- only shared/domain, shared/application, and design-system.
public object ComposerModule
