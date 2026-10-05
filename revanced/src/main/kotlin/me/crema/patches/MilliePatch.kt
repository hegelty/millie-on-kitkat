@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package me.crema.patches

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.PatchType
import app.revanced.patcher.patch.ResourcePatchBuilder

private object Payloads {
    fun read(name: String): ByteArray = checkNotNull(javaClass.classLoader!!.getResourceAsStream("millie/$name")) {
        "Missing Millie patch payload: $name"
    }.use { it.readBytes() }
}

// Patcher 22.0.0 and Manager 2.6.0's 22.0.2-dev.1 incorrectly construct
// RawResourcePatchBuilder with RESOURCE type. Use the explicit RAW_RESOURCE
// constructor so manifest/resources and DEX placement are preserved.
// This dependency on an internal constructor is pinned and checked by SelfTest.
val millieKitKatTlsPatch = ResourcePatchBuilder(PatchType.RAW_RESOURCE).let { builder ->
    builder.compatibleWith("kr.co.millie.eink" to MillieCore.Version.entries.map { it.appVersion }.toSet())
    builder.apply {
        try {
            val version = MillieCore.identifyVersion(get("AndroidManifest.xml").readBytes(), Payloads::read)
            val profile = MillieCore.inputProfile(version, Payloads::read)
            for (index in version.dexCount + 1..9) {
                require(!get("classes$index.dex").exists()) { "Unexpected DEX layout for ${version.appVersion}" }
            }
            require(!get("lib/armeabi-v7a/libconscrypt_jni.so").exists()) { "This APK already contains Conscrypt" }
            val files = profile.stringPropertyNames().associateWith { get(it).readBytes() }
            // Validate and calculate every output before writing any patched resource.
            val changed = MillieCore.patch(files, Payloads::read)
            changed.forEach { (name, bytes) ->
                get(name, false).apply { parentFile.mkdirs(); writeBytes(bytes) }
            }
        } catch (failure: Exception) {
            throw PatchException("Millie e-ink patch failed: ${failure.message}", failure)
        }
    }
    builder.build(
    "Millie e-ink KitKat TLS",
    "Adds Conscrypt and startup compatibility fixes to original Millie e-ink 2.1.0.0 and 2.4.0.0 for ARMv7 Android 4.4. The file named 2.5.0.0 is not supported. Select this patch alone.",
    true,
    )
}
