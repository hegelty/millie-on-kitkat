#!/usr/bin/env python3
"""Build an Android/JVM ReVanced bundle with pinned public tools, without Maven credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

from generate_site import generate

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
WORK = REPO / "work/revanced"
TOOLS = WORK / "tools"
CLI = TOOLS / "revanced-cli-6.0.0-all.jar"
VERSION = "0.2.5"


def java(*args):
    executable = os.environ.get("MILLIE_JAVA") or shutil.which("java")
    if not executable:
        raise RuntimeError("JDK 17+ is required. Add java to PATH or set MILLIE_JAVA to its executable.")
    subprocess.run([executable] + list(map(str, args)), cwd=REPO, check=True)


def download_tools():
    TOOLS.mkdir(parents=True, exist_ok=True)
    for name, metadata in json.loads((ROOT / "tools.lock.json").read_text()).items():
        dest = TOOLS / name
        if not dest.exists():
            print(f"Downloading {name}", flush=True)
            temporary = dest.with_suffix(dest.suffix + ".download")
            urllib.request.urlretrieve(metadata["url"], temporary)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != metadata["sha256"]:
                raise RuntimeError(f"Download checksum mismatch: {name}")
            temporary.replace(dest)
        if hashlib.sha256(dest.read_bytes()).hexdigest() != metadata["sha256"]:
            raise RuntimeError(f"Tool checksum mismatch: {name}")


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 3, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-apk", type=Path, action="append", default=[], help="Run core checks against a local original APK (repeat for both versions)")
    parser.add_argument("--reject-apk", type=Path, action="append", default=[], help="Verify an unsupported APK is rejected (requires --test-apk)")
    args = parser.parse_args()
    if args.reject_apk and not args.test_apk:
        parser.error("--reject-apk requires at least one --test-apk")
    java("-version")
    download_tools()
    output = WORK / "patch-classes"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir()
    sources = sorted((ROOT / "src/main/kotlin").rglob("*.kt"))
    java("-cp", str(TOOLS / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
         "-Xskip-prerelease-check", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
         "-cp", CLI, "-d", output, *sources)
    entries = {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob("*") if p.is_file()}
    entries.update({"millie/" + p.relative_to(ROOT / "payloads").as_posix(): p.read_bytes()
                    for p in (ROOT / "payloads").rglob("*") if p.is_file()})
    with zipfile.ZipFile(TOOLS / "conscrypt-android-2.5.2.aar") as aar:
        native = aar.read("jni/armeabi-v7a/libconscrypt_jni.so")
    assert hashlib.sha256(native).hexdigest() == (ROOT / "payloads/conscrypt.sha256").read_text().strip()
    entries["millie/libconscrypt_jni.so"] = native
    for p in (ROOT / "licenses").iterdir():
        entries["META-INF/licenses/" + p.name] = p.read_bytes()
    entries["META-INF/MANIFEST.MF"] = (
        "Manifest-Version: 1.0\r\nName: Millie e-ink patches\r\n"
        "Description: KitKat TLS compatibility for Millie e-ink 2.1.0.0 and 2.4.0.0\r\n"
        f"Version: {VERSION}\r\nAuthor: millie-eink-repatch\r\n\r\n"
    ).encode()
    jvm = WORK / "patches-jvm.jar"
    write_zip(jvm, entries)
    android = WORK / "patches-android.zip"
    if android.exists():
        android.unlink()
    java("-cp", TOOLS / "r8-8.13.17.jar", "com.android.tools.r8.D8", "--release",
         "--min-api", "26", "--classpath", CLI, "--output", android, jvm)
    with zipfile.ZipFile(android) as archive:
        for name in archive.namelist():
            if name.endswith(".dex"):
                entries[name] = archive.read(name)
    assert "classes.dex" in entries, "Manager requires Android DEX inside the RVP"
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    bundle = dist / f"millie-eink-patches-{VERSION}.rvp"
    write_zip(bundle, entries)
    (dist / "SHA256SUMS").write_text(f"{hashlib.sha256(bundle.read_bytes()).hexdigest()}  {bundle.name}\n")
    java("-jar", CLI, "list-patches", "-p", bundle, "-b")
    if args.test_apk:
        tests = WORK / "test-classes"
        tests.mkdir(exist_ok=True)
        cp = os.pathsep.join(map(str, [CLI, bundle]))
        java("-cp", str(TOOLS / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
             "-Xskip-prerelease-check", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
             "-cp", cp, "-d", tests, *sorted((ROOT / "src/test/kotlin").rglob("*.kt")))
        java("-cp", cp + os.pathsep + str(tests), "me.crema.patches.SelfTestKt",
             *[str(p.resolve()) for p in args.test_apk],
             *(["--reject"] + [str(p.resolve()) for p in args.reject_apk] if args.reject_apk else []))
    generate(REPO, VERSION)
    print(f"Built {bundle}", flush=True)


if __name__ == "__main__":
    main()
