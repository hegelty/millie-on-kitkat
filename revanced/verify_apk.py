#!/usr/bin/env python3
"""Independently verify the final signed APK's ZIP, DEX and encrypted integrity records."""
import argparse
import hashlib
import gzip
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile
import zlib


def decode(asset):
    assert len(asset) >= 104 and (len(asset) - 88) % 16 == 0
    split = min(1024, len(asset) - 88)
    metadata = asset[split:split + 88]
    size = struct.unpack_from("<I", metadata)[0]
    plain = subprocess.run([
        "openssl", "enc", "-d", "-aes-256-cbc", "-nopad", "-K", metadata[8:40].hex(),
        "-iv", metadata[40:56].hex(),
    ], input=asset[:split] + asset[split + 88:], check=True, capture_output=True).stdout
    assert 0 < size <= len(plain) and len(plain) - size < 16
    assert plain[size:] == b" " * (len(plain) - size)
    plain = plain[:size]
    assert hashlib.md5(plain).hexdigest().encode() == metadata[56:88]
    return plain


def dex_info(data):
    assert data[:8] == b"dex\n035\0"
    assert struct.unpack_from("<II", data, 32) == (len(data), 112)
    assert hashlib.sha1(data[32:]).digest() == data[12:32]
    assert zlib.adler32(data[12:]) == struct.unpack_from("<I", data, 8)[0]
    return {"bytes": len(data), "crc32": f"{zlib.crc32(data):08x}",
            "classes": struct.unpack_from("<I", data, 96)[0],
            "sha256": hashlib.sha256(data).hexdigest()}


def unpack_dex(asset):
    with zipfile.ZipFile(io.BytesIO(decode(asset))) as archive:
        assert archive.namelist() == ["classes.dex"]
        assert archive.getinfo("classes.dex").file_size <= 16_777_216
        return archive.read("classes.dex")


def method_instructions(data, descriptor, name):
    """Locate one unambiguous method without relying on DEX file offsets."""
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
        raise AssertionError("Invalid ULEB128")

    strings = []
    for index in range(u32(56)):
        _, start = uleb(u32(u32(60) + index * 4))
        strings.append(data[start:data.index(0, start)])
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    matches = []
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        if types[u32(offset)] != descriptor.encode():
            continue
        cursor = u32(offset + 24)
        assert cursor
        sizes = []
        for _ in range(4):
            size, cursor = uleb(cursor)
            sizes.append(size)
        for _ in range(sizes[0] + sizes[1]):
            _, cursor = uleb(cursor)
            _, cursor = uleb(cursor)
        for count in sizes[2:]:
            method_id = 0
            for _ in range(count):
                difference, cursor = uleb(cursor)
                method_id += difference
                _, cursor = uleb(cursor)
                code, cursor = uleb(cursor)
                if strings[u32(u32(92) + method_id * 8 + 4)] == name.encode():
                    assert code
                    matches.append(data[code + 16:code + 16 + u32(code + 12) * 2])
    assert len(matches) == 1, (descriptor, name, len(matches))
    return matches[0]


def verify(original, patched, touch=False):
    payloads = Path(__file__).resolve().parent / "payloads"
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(patched) as after:
        assert before.testzip() is None
        assert after.testzip() is None, "ZIP CRC must match content after signing"
        assert len(before.namelist()) == len(set(before.namelist())), "Duplicate input entries"
        assert len(after.namelist()) == len(set(after.namelist())), "Duplicate output entries"
        profiles = {}
        for version, folder in [("2.1.0.0", payloads), ("2.4.0.0", payloads / "2.4.0.0")]:
            profile = dict(line.split("=", 1) for line in (folder / "inputs.properties").read_text().splitlines()
                           if line and not line.startswith("#"))
            profiles[version] = (folder, profile)
        versions = [v for v, (_, p) in profiles.items()
                    if p["AndroidManifest.xml"] == hashlib.sha256(before.read("AndroidManifest.xml")).hexdigest()]
        assert len(versions) == 1, "Unsupported original APK"
        version = versions[0]
        folder, profile = profiles[version]
        for name, expected_hash in profile.items():
            assert hashlib.sha256(before.read(name)).hexdigest() == expected_hash, name
        assert not touch or version == "2.4.0.0", "Touch patch supports only 2.4.0.0"
        count = 5 if version == "2.1.0.0" else 1
        dex_names = ["classes.dex", *[f"classes{i}.dex" for i in range(2, count + 1)]]
        for archive in (before, after):
            assert sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)) == dex_names
        changed = [n for n in before.namelist() if not n.startswith("META-INF/") and before.read(n) != after.read(n)]
        secondary = "classes5.dex" if count == 5 else "assets/classes3.jet"
        expected_changes = {"classes.dex", secondary, "assets/m7a", "assets/agconfig"}
        if touch:
            expected_changes.add("assets/classes.jet")
        assert set(changed) == expected_changes, changed
        additions = set(after.namelist()) - set(before.namelist())
        native = "lib/armeabi-v7a/libconscrypt_jni.so"
        assert {n for n in additions if not n.startswith("META-INF/")} == {native}
        assert hashlib.sha256(after.read(native)).hexdigest() == "ea54515c67cd123fd33d398c036849de9a57e0c148052348e828aed6381d72d0"
        dex = {name: dex_info(after.read(name)) for name in dex_names}
        targets = {"classes.dex": after.read("classes.dex")}
        if count == 5:
            targets["classes5.dex"] = after.read("classes5.dex")
        else:
            event_dex = unpack_dex(after.read("assets/classes.jet"))
            dex["assets/classes.jet!classes.dex"] = dex_info(event_dex)
            if touch:
                targets["classes1.dex"] = event_dex
            else:
                assert b"Lme/crema/millietls/ContentEventMapper;" not in event_dex
            old_inner, inner = unpack_dex(before.read(secondary)), unpack_dex(after.read(secondary))
            dex["assets/classes3.jet!classes.dex"] = dex_info(inner)
            targets["classes3-touch.dex" if touch else "classes3.dex"] = inner
            callback = "Lkr/co/millie/eink/SplashActivity$getAppUpdateNoticeInfo$1;"
            assert method_instructions(old_inner, callback, "onResponse")[28:32] == bytes.fromhex("39080300")
            assert method_instructions(inner, callback, "onResponse")[28:32] == bytes.fromhex("29006800")
            if touch:
                method_instructions(inner, "Lme/crema/millietls/EpubTouchCompat;", "rewrite")
            else:
                assert b"Lme/crema/millietls/EpubTouchCompat;" not in inner
            assert b"MillieTouch" not in inner and b"CREMA_TRACE" not in inner
        for name, data in targets.items():
            delta = gzip.decompress((folder / (name + ".delta.gz")).read_bytes())
            assert delta[:6] == b"MDLT01"
            assert hashlib.sha256(data).digest() == delta[38:70], name
        old_module = decode(before.read("assets/m7a"))
        module = decode(after.read("assets/m7a"))
        match = re.search(rb"gc1:([\x00-\x0f]{128});", module)
        assert match is not None
        stored = bytes((a << 4) | b for a, b in zip(match[1][::2], match[1][1::2])).decode()
        expected = "".join(dex[name]["crc32"] for name in dex_names) + "41424344" * (8 - count)
        assert stored == expected
        allowed = set(range(match.start(1), match.end(1)))
        for offset, old, new in [(0x1d09be, "00f0b9f8", "012000bf"), (0xe8c30, "9501feeb", "0000a0e1")]:
            assert old_module[offset:offset + 4] == bytes.fromhex(old)
            assert module[offset:offset + 4] == bytes.fromhex(new)
            allowed.update(range(offset, offset + 4))
        assert len(module) == len(old_module)
        assert all(i in allowed for i, (a, b) in enumerate(zip(old_module, module)) if a != b)
        old_config = decode(before.read("assets/agconfig"))
        config = decode(after.read("assets/agconfig"))
        expected_config = re.sub(rb"141:[0-9a-f]{64};",
                                 b"141:" + hashlib.sha256(after.read("assets/m7a")).hexdigest().encode() + b";",
                                 old_config)
        if count == 5:
            expected_config = re.sub(rb"110:[0-9a-f]{64};", b"110:" + expected.encode() + b";", expected_config)
        assert config == expected_config, "Unexpected config change (2.4 must preserve 110)"
        assert b"141:" + hashlib.sha256(after.read("assets/m7a")).hexdigest().encode() + b";" in config
        return {"app_version": version, "touch_patch": touch, "zip_crc_valid": True, "dex_count": count, "dex": dex,
                "changed_original_entries": sorted(changed), "gc1_matches": True,
                "config_110": "updated" if count == 5 else "preserved",
                "config_141_matches": True, "module_changes_limited": True,
                "apk_sha256": hashlib.sha256(Path(patched).read_bytes()).hexdigest()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("patched", type=Path)
    parser.add_argument("--touch", action="store_true", help="Expect the optional EPUB touch patch in addition to TLS")
    args = parser.parse_args()
    print(json.dumps(verify(args.original, args.patched, args.touch), indent=2))
