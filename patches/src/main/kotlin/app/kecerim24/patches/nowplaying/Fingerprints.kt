package app.kecerim24.patches.nowplaying

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Static initializer of the object holding the music services Now Playing integrates with:
 *
 * ```
 * val SUPPORTED_PACKAGES = listOf("com.apple.android.music", ..., "deezer.android.app")
 * val HOST_TO_PACKAGE = mapOf(
 *     "www.deezer.com" to "deezer.android.app",
 *     "music.apple.com" to "com.apple.android.music",
 *     "music.youtube.com" to "com.google.android.youtube",
 * )
 * ```
 *
 * The list gates which installed apps are offered as the connected music service, and which
 * song links are turned into play buttons.
 */
internal object SupportedMusicServicesFingerprint : Fingerprint(
    name = "<clinit>",
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = MusicService.entries.map { it.packageName } + listOf(
        "www.deezer.com",
        "music.apple.com",
        "music.youtube.com"
    )
)

/**
 * `MusicServiceRepositoryImpl.buildPlayActionForPackage(...)`, which builds the play button for
 * the connected music service. Its class holds every other hardcoded package comparison and
 * intent target (YouTube and YouTube Music deep links, Spotify's search query format).
 */
internal object BuildPlayActionFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;"
    ),
    strings = listOf(
        "buildPlayActionForPackage",
        "Package %s is not installed."
    )
)

/**
 * The method of `MusicServiceRepositoryImpl` that turns a recognised song's links into play
 * actions. For an `android-app://<package>/...` link it takes the package from the URI host:
 *
 * ```
 * "android-app" -> uri.host
 * ```
 *
 * The match ends on the `move-result-object` holding that host.
 */
internal object SongLinkPackageFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    filters = listOf(
        string("android-app"),
        methodCall(
            definingClass = "Landroid/net/Uri;",
            name = "getHost"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    ),
    strings = listOf("https://open.spotify.com/track/%s")
)
