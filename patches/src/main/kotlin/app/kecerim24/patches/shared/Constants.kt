package app.kecerim24.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {

    /**
     * dream Player, the free Android phone/tablet Enigma2 client by CyberDream.
     *
     * Not to be confused with "dream Player TV" (de.cyberdream.dreamepg.tv.player),
     * which is a separate app with its own obfuscation mapping.
     */
    val COMPATIBILITY_DREAM_PLAYER = Compatibility(
        name = "dream Player",
        packageName = "de.cyberdream.dreamepg.player",
        apkFileType = ApkFileType.APKS,
        appIconColor = 0x2C5C80,
        // The app uses APK signature scheme v3.1 key rotation, so a Play delivered build
        // carries two signers: the original CyberDream key for SDK 24-32, and the rotated
        // Google Play app signing key for SDK 33 and above. Both are part of the same
        // lineage and either may be reported depending on which scheme is inspected.
        signatures = setOf(
            // O=CyberDream, L=Karlsruhe (minSdkVersion 24-32)
            "ff84631633d9b99afc1fdc5e21bfaa69a0ea285c440cf3f542ab58964fe91f97",
            // Rotated Play app signing key (minSdkVersion 33+)
            "600d4c2fd24a9cd98bdc8aec255e537a47a2f5b0537cd10c67c9fb79ac088812"
        ),
        targets = listOf(
            AppTarget(
                version = null,
                isExperimental = true
            ),
            // Developed and confirmed working against 14.1.0 (version code 51098).
            AppTarget(
                version = "14.1.0"
            )
        )
    )

    /**
     * Settle Up, a group expense splitter by Step Up Labs.
     *
     * The app id is cz.destil.settleup, but the code lives in io.stepuplabs.settleup.
     */
    val COMPATIBILITY_SETTLE_UP = Compatibility(
        name = "Settle Up",
        packageName = "cz.destil.settleup",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xE47048,
        signatures = setOf(
            // O=Step Up Labs (single signer, no key rotation)
            "e3cc443274d899c540326e620ae730112316a9d02e566a6e3dbbcbdf11c01264"
        ),
        targets = listOf(
            AppTarget(
                version = null,
                isExperimental = true
            ),
            // Developed and confirmed working against 11.0.2280 (version code 2280).
            AppTarget(
                version = "11.0.2280"
            )
        )
    )

    /**
     * Merlin Bird ID, the free bird identification app by the Cornell Lab of Ornithology.
     *
     * The app id is com.labs.merlinbirdid.app; the code lives in edu.cornell.birds.merlin and
     * com.labs.merlinbirdid.
     */
    val COMPATIBILITY_MERLIN = Compatibility(
        name = "Merlin Bird ID",
        packageName = "com.labs.merlinbirdid.app",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xEC1C24,
        signatures = setOf(
            // CN=Unknown, OU=Cornell, O=Cornell (single signer, v3 only, no key rotation)
            "4bf47cfe281374265431aacc08fdbf5be4fbcecad4868f352be6468b8652988d"
        ),
        targets = listOf(
            AppTarget(
                version = null,
                isExperimental = true
            ),
            // Developed and confirmed working against 4.1 (version code 4010103).
            AppTarget(
                version = "4.1"
            )
        )
    )
}
