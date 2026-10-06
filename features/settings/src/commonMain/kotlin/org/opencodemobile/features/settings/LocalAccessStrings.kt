package org.opencodemobile.features.settings

import org.opencodemobile.design.system.i18n.AppLanguage

/**
 * FR/EN copy for the §7.3 local-access settings surface.
 *
 * The rest of the app renders copy through the design-system
 * `composeResources` catalogue. This surface cannot: the iOS app links a single
 * *static* `iosAppHost` framework, and Xcode does not embed that framework's
 * Compose resources, so the first `stringResource(...)` on iOS aborts at
 * startup. The settings screen is the first shared Compose UI hosted on iOS, so
 * it carries its own bilingual copy until iOS Compose-resource embedding is
 * wired (follow-up). Both platforms therefore render the same strings.
 *
 * Selected from the device locale, exactly like `Res.string.*` would be.
 */
public class LocalAccessStrings(
    language: AppLanguage = AppLanguage.current(),
) {
    private val english: Boolean = language == AppLanguage.English

    public val open: String = if (english) "Settings" else "Réglages"
    public val back: String = if (english) "Back" else "Retour"
    public val title: String = if (english) "Local access" else "Accès local"

    public val optionalBiometrics: String =
        if (english) "Optional biometrics" else "Biométrie optionnelle"
    public val optionalBiometricsDescription: String = if (english) {
        "Ask for Face ID, Touch ID or BiometricPrompt when you open a protected screen. Optional."
    } else {
        "Demander Face ID, Touch ID ou BiometricPrompt à l’ouverture d’un écran protégé. Facultatif."
    }
    public val optionalBiometricsNote: String = if (english) {
        "Turning this off gives access to no server credential. Server authentication stays " +
            "independent from this device setting."
    } else {
        "La désactiver ne donne accès à aucun credential serveur. L’authentification serveur reste " +
            "indépendante de ce réglage local."
    }

    public val multitaskMasking: String =
        if (english) "Hide content in the app switcher" else "Masquer le contenu dans le sélecteur d’apps"
    public val multitaskMaskingDescription: String = if (english) {
        "Cover the transcript when the app goes to the background. On by default."
    } else {
        "Recouvrir la transcription quand l’app passe en arrière-plan. Activé par défaut."
    }

    public val screenCaptureBlocking: String = if (english) {
        "Block screenshots and screen recording"
    } else {
        "Bloquer les captures d’écran et l’enregistrement"
    }
    public val screenCaptureBlockingDescription: String = if (english) {
        "Ask the OS to block captures. Off by default; independent from the app-switcher cover."
    } else {
        "Demander au système de bloquer les captures. Désactivé par défaut ; indépendant du " +
            "masquage du sélecteur d’apps."
    }
    public val screenCaptureBlockingNote: String = if (english) {
        "On Android this blocks screenshots and screen recording too (FLAG_SECURE). The " +
            "app-switcher cover stays available either way."
    } else {
        "Sur Android, cela bloque aussi les captures d’écran et l’enregistrement (FLAG_SECURE). " +
            "Le masquage du sélecteur d’apps reste disponible dans tous les cas."
    }
    public val screenCaptureBlockingUnavailable: String = if (english) {
        "Screen capture blocking is not available on this platform. The app-switcher cover still " +
            "hides the content."
    } else {
        "Le blocage des captures n’est pas disponible sur cette plateforme. Le masquage du " +
            "sélecteur d’apps continue de recouvrir le contenu."
    }
}
