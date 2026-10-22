package app.kecerim24.patches.settleup

import app.kecerim24.patches.shared.Constants.COMPATIBILITY_SETTLE_UP
import app.kecerim24.patches.shared.pairipLicenseCheckPatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "Removes the rewarded ads shown after adding expenses.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SETTLE_UP)

    // Google Play's PairIP check kills any re-signed build on startup.
    dependsOn(pairipLicenseCheckPatch)

    execute {
        // region Tell the app there are no ads to show.

        // IsPremium is the single object carrying the premium state, and its showAds flag is
        // what makes the app count expenses towards the next ad. Forcing it false in the
        // constructor covers every place the state is built.
        IsPremiumConstructorFingerprint.method.addInstruction(
            0,
            "const/4 p2, 0x0"
        )

        // endregion

        // region Never open the ad screen.

        // Belt and braces: the legacy pre-Compose screens keep their own expense counter that
        // does not consult showAds, so also make the entry point to the rewarded ad screen a
        // no-op.
        BeforeAdActivityStartFingerprint.method.addInstructions(
            0,
            "return-void"
        )

        // The legacy expense screen does not go through start(): R8 inlined a copy of it that
        // builds the intent directly. Close the screen as soon as it is created, which catches
        // every way in. This has to come after the Hilt onCreate, which calls super.onCreate,
        // or Android throws SuperNotCalledException; finishing before setContent means nothing
        // is drawn.
        BeforeAdActivityOnCreateFingerprint.method.apply {
            val superOnCreateIndex = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)
                    ?.name?.startsWith("onCreate") == true
            }
            check(superOnCreateIndex >= 0) { "BeforeAdActivity.onCreate has no super.onCreate call" }

            addInstructions(
                superOnCreateIndex + 1,
                """
                    invoke-virtual { p0 }, Landroid/app/Activity;->finish()V
                    return-void
                """
            )
        }

        // endregion
    }
}
