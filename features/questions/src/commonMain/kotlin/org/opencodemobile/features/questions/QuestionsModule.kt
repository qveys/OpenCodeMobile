package org.opencodemobile.features.questions

// V1-07 pending-question surface: the non-dismissable question banner, the
// option selection, and the only two allowed actions (reply / reject). There is
// no dismiss affordance and no swipe-to-dismiss wrapper: a question disappears
// only when the server stops listing it. All state and gates (server is the
// source of truth, offline read-only, answer/reject exactly as captured) live in
// `org.opencodemobile.shared.application.interaction.PendingQuestionsController`;
// this module only presents and forwards intents.
//
// Must not depend on shared/networking, shared/persistence, shared/security, or
// another feature's internals (§5.2) -- only shared/domain, shared/application,
// and design-system.
public object QuestionsModule
