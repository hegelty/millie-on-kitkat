@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package me.crema.patches

import app.revanced.patcher.patch.PatchType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun main(args: Array<String>) {
    fun payload(name: String) = checkNotNull(MillieCore::class.java.classLoader.getResourceAsStream("millie/$name")).use { it.readBytes() }
    fun rejects(label: String, block: () -> Unit) {
        check(runCatching(block).isFailure) { "Expected rejection: $label" }
        println("PASS rejection: $label")
    }
    fun readApk(path: String): Map<String, ByteArray> = ZipFile(File(path)).use { zip ->
        val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
        val version = MillieCore.identifyVersion(manifest, ::payload)
        val names = MillieCore.inputProfile(version, ::payload).stringPropertyNames().toMutableSet()
        names += zip.entries().asSequence().map { it.name }.filter {
            Regex("classes\\d*\\.dex").matches(it) || it == "lib/armeabi-v7a/libconscrypt_jni.so"
        }.toList()
        names.associateWith { name -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }
    }
    check(millieKitKatTlsPatch.type == PatchType.RAW_RESOURCE)
    println("PASS raw-resource patch type")
    var rejectInput = false
    for (arg in args) {
        if (arg == "--reject") { rejectInput = true; continue }
        if (rejectInput) {
            rejects("unsupported APK: ${File(arg).name}") { MillieCore.patch(readApk(arg), ::payload) }
            continue
        }
        val files = readApk(arg)
        val version = MillieCore.identifyVersion(files.getValue("AndroidManifest.xml"), ::payload)
        val changed = MillieCore.patch(files, ::payload)
        val expected = if (version == MillieCore.Version.V21) mapOf(
            "classes.dex" to "fa4c3d7ca5b60f8db19743317939d90d3d633a5bf0a6329110937ea235b147e1",
            "classes5.dex" to "30c6a57c57a6f258b451067705647ea138ad8787a8c2f4f5680aeeaa994bdba3",
            "assets/m7a" to "6be98e8cdf5ad8ab9f9ce5fdad7f0fe9dbadab68eb3ea3e86cae3cb52c8fe3a3",
            "assets/agconfig" to "35d706e85e6f53f774720e26b3194db505a8eed95811bffa1fdc55730b9ef553",
        ) else mapOf(
            "classes.dex" to "f31ab6af3f6994c4bd100aefcb2d2b6af8171c2ab23bacf79fe043e93e5b3b18",
            "assets/m7a" to "66745767fa9f3b232d5f9b267a16aff111447297593b6ae7979ea006eee95cc6",
            "assets/agconfig" to "78f97f6616f78159896df68d9c19da335adc184fe653e4c9e9dd4490b92524a5",
        )
        val nativePath = "lib/armeabi-v7a/libconscrypt_jni.so"
        val packedPaths = if (version == MillieCore.Version.V24) setOf("assets/classes3.jet") else emptySet()
        check(changed.keys == expected.keys + nativePath + packedPaths)
        // Golden values are from the previously signed, runtime-tested APKs.
        for ((name, hash) in expected) check(MillieCore.sha256(changed.getValue(name)) == hash) { "Golden mismatch: $name" }
        check(MillieCore.sha256(changed.getValue(nativePath)) == "ea54515c67cd123fd33d398c036849de9a57e0c148052348e828aed6381d72d0")
        for (name in version.dexNames) MillieCore.validateDex(changed[name] ?: files.getValue(name))
        if (version == MillieCore.Version.V24) {
            ZipInputStream(ByteArrayInputStream(MillieCore.decodeAsset(changed.getValue("assets/classes3.jet")).first)).use { zip ->
                check(zip.nextEntry.name == "classes.dex")
                val dex = zip.readBytes()
                MillieCore.validateDex(dex)
                check(MillieCore.sha256(dex) == "0f385db480ba8db79d261b6ba4f24ccce382267ee7d24cc78dce8e1f127833fb")
                check(zip.nextEntry == null)
            }
            val asset = files.getValue("assets/classes3.jet")
            val delta = payload("${version.prefix}classes3.dex.delta.gz")
            rejects("packed delta corruption") { MillieCore.patchPackedDex(asset, delta.copyOf(24)) }
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("unexpected.dex")); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
            rejects("unexpected packed ZIP entry") { MillieCore.patchPackedDex(MillieCore.encodeAsset(out.toByteArray(), asset), delta) }
        }
        val module = files.getValue("assets/m7a")
        check(MillieCore.encodeAsset(MillieCore.decodeAsset(module).first, module).contentEquals(module))
        for (size in listOf(1, 16, 17, 1023, 1024, 1025)) {
            val sample = ByteArray(size) { (it * 17).toByte() }
            check(MillieCore.decodeAsset(MillieCore.encodeAsset(sample, module)).first.contentEquals(sample))
        }
        println("PASS ${version.appVersion}: golden outputs, DEX layout and asset round trips")
        for (name in files.keys) rejects("modified input: $name") {
            val damaged = files.getValue(name).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            MillieCore.patch(files + (name to damaged), ::payload)
        }
        rejects("already patched APK") { MillieCore.patch(files + changed, ::payload) }
        rejects("extra DEX") { MillieCore.patch(files + ("classes${version.dexCount + 1}.dex" to byteArrayOf(1)), ::payload) }
        rejects("pre-existing Conscrypt") { MillieCore.patch(files + (nativePath to byteArrayOf(1)), ::payload) }
        rejects("truncated delta") { MillieCore.applyDelta(files.getValue("classes.dex"), payload("${version.prefix}classes.dex.delta.gz").copyOf(24)) }
        rejects("corrupt encrypted asset") { MillieCore.decodeAsset(module.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) }
    }
    println("All core checks passed")
}
