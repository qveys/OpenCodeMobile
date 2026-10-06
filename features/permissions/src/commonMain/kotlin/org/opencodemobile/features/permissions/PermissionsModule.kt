package org.opencodemobile.features.permissions

// V1-06 permission surface: the non-dismissable pending banner, the foreground +
// authenticated confirmation screen, and the OP4 notification rule. The banner
// (`PermissionBanner`) has no dismiss affordance and no swipe-to-dismiss wrapper;
// the confirmation screen (`PermissionConfirmationScreen`) is the only surface
// that submits a decision. All gates (offline read-only, foreground, biometric,
// content binding, exact decision relay) live in
// `org.opencodemobile.shared.application.permission.PermissionCoordinator`; this
// module only presents and forwards intents.
//
// Must not depend on shared/networking, shared/persistence, shared/security, or
// another feature's internals (§5.2) -- only shared/domain, shared/application,
// and design-system.
public object PermissionsModule
