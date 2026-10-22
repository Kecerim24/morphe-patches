package app.kecerim24.patches.nowplaying

import app.kecerim24.patches.shared.Constants.COMPATIBILITY_NOW_PLAYING
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element
import java.util.logging.Logger

private val logger = Logger.getLogger("ChangeMusicServicePackagesPatch")

/**
 * The music apps Now Playing has built-in support for, by the package name it expects.
 *
 * [presets] are well known alternative builds offered as option values.
 */
internal enum class MusicService(
    val title: String,
    val packageName: String,
    val presets: Map<String, String> = emptyMap()
) {
    APPLE_MUSIC("Apple Music", "com.apple.android.music"),
    DEEZER("Deezer", "deezer.android.app"),
    PANDORA("Pandora", "com.pandora.android"),
    SOUNDCLOUD("SoundCloud", "com.soundcloud.android"),
    SPOTIFY("Spotify", "com.spotify.music"),
    TIDAL("TIDAL", "com.aspiro.tidal"),
    YOUTUBE(
        "YouTube",
        "com.google.android.youtube",
        mapOf(
            "Morphe" to "app.morphe.android.youtube",
            "ReVanced" to "app.revanced.android.youtube"
        )
    ),
    YOUTUBE_MUSIC(
        "YouTube Music",
        "com.google.android.apps.youtube.music",
        mapOf(
            "Morphe" to "app.morphe.android.apps.youtube.music",
            "ReVanced" to "app.revanced.android.apps.youtube.music"
        )
    );

    val optionKey = name.lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        .replaceFirstChar(Char::lowercase) + "Package"
}

private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/**
 * Points Now Playing at different builds of the music apps it supports.
 *
 * Now Playing only offers an app as the connected music service, and only builds play buttons
 * for it, when its package name is on a hardcoded list. A renamed build, such as a patched
 * YouTube Music installed next to the original, is therefore invisible to it. Every option
 * defaults to the stock package, so only the services that are changed are touched.
 *
 * The patch does three things:
 *
 * 1. rewrites the package names in the supported list, the host to package map for web links,
 *    and every comparison and intent target in `MusicServiceRepositoryImpl`;
 * 2. maps `android-app://<stock package>/...` song links to the new package, since those carry
 *    the stock package name in the link itself;
 * 3. swaps the packages in the manifest `<queries>`. Since Android 11 an app can only see the
 *    packages it declares there, so without this the new package would look uninstalled.
 */
@Suppress("unused")
val changeMusicServicePackagesPatch = bytecodePatch(
    name = "Change music service packages",
    description = "Changes the package names of the supported music apps, so that Now Playing " +
        "can open patched or renamed builds of them.",
    default = false
) {
    compatibleWith(COMPATIBILITY_NOW_PLAYING)

    val packageOptions = MusicService.entries.associateWith { service ->
        stringOption(
            key = service.optionKey,
            default = service.packageName,
            values = mapOf("Original" to service.packageName) + service.presets,
            title = service.title,
            description = "The package name of the ${service.title} app to use.",
            required = true
        ) { it != null && PACKAGE_NAME_REGEX.matches(it) }
    }

    // Stock package name to the new one, for the services that are changed.
    val replacements by lazy {
        packageOptions.entries
            .associate { (service, option) -> service.packageName to option.value!! }
            .filter { (stock, new) -> stock != new }
    }

    dependsOn(
        resourcePatch {
            execute {
                if (replacements.isEmpty()) return@execute

                document("AndroidManifest.xml").use { document ->
                    val packages = document.getElementsByTagName("package")
                    for (i in 0 until packages.length) {
                        val element = packages.item(i) as Element
                        if (element.parentNode.nodeName != "queries") continue

                        replacements[element.getAttribute("android:name")]?.let {
                            element.setAttribute("android:name", it)
                        }
                    }
                }
            }
        }
    )

    execute {
        if (replacements.isEmpty()) {
            logger.warning("No package was changed from the original, so nothing was patched")
            return@execute
        }

        // region Rewrite the hardcoded package names.

        val classes = listOf(
            SupportedMusicServicesFingerprint.classDef,
            BuildPlayActionFingerprint.classDef
        )

        val replaced = mutableSetOf<String>()
        classes.flatMap { it.methods }.forEach { method ->
            val instructions = method.instructionsOrNull ?: return@forEach

            instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO
            }.toList().forEach { (index, instruction) ->
                val string = ((instruction as ReferenceInstruction).reference as StringReference).string
                val new = replacements[string] ?: return@forEach
                val register = (instruction as OneRegisterInstruction).registerA

                method.replaceInstruction(index, "const-string v$register, \"$new\"")
                replaced += string
            }
        }

        // Every service is on the supported list, so a miss means the app has changed.
        (replacements.keys - replaced).forEach {
            logger.warning("$it was not found in the app, so it could not be changed")
        }

        // endregion

        // region Map android-app:// song links to the new packages.

        SongLinkPackageFingerprint.let {
            val method = it.method
            val matches = it.instructionMatches

            // The register the scheme literal was compared with is dead from here on, as the
            // branch only goes on to read the host.
            val freeRegister = matches[0].getInstruction<OneRegisterInstruction>().registerA
            val hostRegister = matches[2].getInstruction<OneRegisterInstruction>().registerA

            val remap = replacements.entries.mapIndexed { i, (stock, new) ->
                """
                    const-string v$freeRegister, "$stock"
                    invoke-virtual { v$freeRegister, v$hostRegister }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :not_$i
                    const-string v$hostRegister, "$new"
                    goto :remapped
                    :not_$i
                """
            }.joinToString("\n")

            method.addInstructionsWithLabels(
                matches[2].index + 1,
                """
                    $remap
                    :remapped
                    nop
                """
            )
        }

        // endregion
    }
}
