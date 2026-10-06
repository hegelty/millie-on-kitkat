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
    check(millieEpubTouchPatch.type == PatchType.RAW_RESOURCE)
    check(millieEpubTouchPatch.dependencies == setOf(millieKitKatTlsPatch))
    check(millieKitKatTlsPatch.use && !millieEpubTouchPatch.use)
    println("PASS patch types, dependency and optional touch default")
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
            "classes.dex" to "25249d0440bf5bcb3eae79312ca7b4431a34607c716e24cf74fe1ae515c6a97d",
            "assets/m7a" to "431807dfb9a260fdb00fb8f6c90dc2ae56763cdaeef9d15d81a002aeed1408ee",
            "assets/agconfig" to "17c93273e292e30bbb160a8b687e90dad1aa646377c3f399a2a522f6cbfc51a0",
        )
        val nativePath = "lib/armeabi-v7a/libconscrypt_jni.so"
        val packedPaths = if (version == MillieCore.Version.V24) setOf("assets/classes3.jet") else emptySet()
        check(changed.keys == expected.keys + nativePath + packedPaths)
        // Golden values are from independently assembled and signed fixture APKs.
        for ((name, hash) in expected) check(MillieCore.sha256(changed.getValue(name)) == hash) { "Golden mismatch: $name" }
        check(MillieCore.sha256(changed.getValue(nativePath)) == "ea54515c67cd123fd33d398c036849de9a57e0c148052348e828aed6381d72d0")
        for (name in version.dexNames) MillieCore.validateDex(changed[name] ?: files.getValue(name))
        if (version == MillieCore.Version.V24) {
            check("assets/classes.jet" !in changed)
            ZipInputStream(ByteArrayInputStream(MillieCore.decodeAsset(changed.getValue("assets/classes3.jet")).first)).use { zip ->
                check(zip.nextEntry.name == "classes.dex")
                check(MillieCore.sha256(zip.readBytes()) == "0f385db480ba8db79d261b6ba4f24ccce382267ee7d24cc78dce8e1f127833fb")
            }
            val touchChanged = MillieCore.patchTouch(files + changed, ::payload)
            check(touchChanged.keys == setOf("assets/classes.jet", "assets/classes3.jet"))
            rejects("touch before TLS dependency") { MillieCore.patchTouch(files, ::payload) }
            rejects("touch applied twice") { MillieCore.patchTouch(files + changed + touchChanged, ::payload) }
            ZipInputStream(ByteArrayInputStream(MillieCore.decodeAsset(touchChanged.getValue("assets/classes.jet")).first)).use { zip ->
                check(zip.nextEntry.name == "classes.dex")
                val dex = zip.readBytes()
                MillieCore.validateDex(dex)
                check(MillieCore.sha256(dex) == "a6ab3d58f8421571c07100089c346a094b3e70d70315e4ca159a92874db6d2db")
                check(zip.nextEntry == null)
            }
            ZipInputStream(ByteArrayInputStream(MillieCore.decodeAsset(touchChanged.getValue("assets/classes3.jet")).first)).use { zip ->
                check(zip.nextEntry.name == "classes.dex")
                val dex = zip.readBytes()
                MillieCore.validateDex(dex)
                check(MillieCore.sha256(dex) == "061a37336962409d781c608429cd738e99381b527b654310e839780e22eac80e")
                check(zip.nextEntry == null)
            }
            rejects("touch delta applied to wrong packed DEX") {
                MillieCore.patchPackedDex(files.getValue("assets/classes3.jet"),
                    payload("${version.prefix}classes1.dex.delta.gz"))
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
        if (version == MillieCore.Version.V21) {
            rejects("touch on 2.1") { MillieCore.patchTouch(files + changed, ::payload) }
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
