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
    "TLS 관련 통신 오류를 수정하고, 업데이트 안내를 제거합니다.",
    true,
    )
}

val millieEpubTouchPatch = ResourcePatchBuilder(PatchType.RAW_RESOURCE).let { builder ->
    builder.compatibleWith("kr.co.millie.eink" to setOf("2.4.0.0"))
    builder.dependsOn(millieKitKatTlsPatch)
    builder.apply {
        try {
            val files = listOf("AndroidManifest.xml", "assets/classes.jet", "assets/classes3.jet")
                .associateWith { get(it).readBytes() }
            val changed = MillieCore.patchTouch(files, Payloads::read)
            changed.forEach { (name, bytes) -> get(name, false).writeBytes(bytes) }
        } catch (failure: Exception) {
            throw PatchException("Millie e-ink EPUB touch patch failed: ${failure.message}", failure)
        }
    }
    builder.build(
        "Millie e-ink EPUB touch",
        "터치 시 지연이 발생하는 문제를 수정합니다.",
        false,
    )
}
