@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package me.crema.patches

import app.revanced.patcher.patch.PatchType
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

fun main(args: Array<String>) {
    fun payload(name: String) = checkNotNull(MillieCore::class.java.classLoader.getResourceAsStream("millie/$name")).use { it.readBytes() }
    fun rejects(label: String, block: () -> Unit) {
        check(runCatching(block).isFailure) { "Expected rejection: $label" }
        println("PASS rejection: $label")
    }
    check(millieKitKatTlsPatch.type == PatchType.RAW_RESOURCE)
    println("PASS patch type preserves raw manifest/resources and DEX layout")
    val names = Properties().apply { load(payload("inputs.properties").inputStream()) }.stringPropertyNames()
    val files = ZipFile(File(args.single())).use { zip ->
        names.associateWith { name -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }
    }
    val changed = MillieCore.patch(files, ::payload)
    check(changed.keys == setOf("classes.dex", "classes5.dex", "assets/m7a", "assets/agconfig", "lib/armeabi-v7a/libconscrypt_jni.so"))
    for (name in listOf("classes.dex", "classes5.dex")) MillieCore.validateDex(changed.getValue(name))
    val module = files.getValue("assets/m7a")
    check(MillieCore.encodeAsset(MillieCore.decodeAsset(module).first, module).contentEquals(module))
    for (size in listOf(1, 16, 17, 1023, 1024, 1025)) {
        val sample = ByteArray(size) { (it * 17).toByte() }
        check(MillieCore.decodeAsset(MillieCore.encodeAsset(sample, module)).first.contentEquals(sample))
    }
    println("PASS original/short/long asset round trips and valid five-DEX outputs")
    rejects("modified source") {
        MillieCore.patch(files + ("classes.dex" to files.getValue("classes.dex").copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }), ::payload)
    }
    rejects("already patched APK") { MillieCore.patch(files + changed, ::payload) }
    rejects("extra DEX") { MillieCore.patch(files + ("classes6.dex" to byteArrayOf(1)), ::payload) }
    rejects("truncated delta") { MillieCore.applyDelta(files.getValue("classes.dex"), payload("classes.dex.delta.gz").copyOf(24)) }
    rejects("corrupt encrypted asset") { MillieCore.decodeAsset(module.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) }
    println("All core checks passed")
}
