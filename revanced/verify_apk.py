#!/usr/bin/env python3
"""Independently verify the final signed APK's ZIP, DEX and encrypted integrity records."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile
import zlib


def decode(asset):
    split = min(1024, len(asset) - 88)
    metadata = asset[split:split + 88]
    size = struct.unpack_from("<I", metadata)[0]
    plain = subprocess.run([
        "openssl", "enc", "-d", "-aes-256-cbc", "-nopad", "-K", metadata[8:40].hex(),
        "-iv", metadata[40:56].hex(),
    ], input=asset[:split] + asset[split + 88:], check=True, capture_output=True).stdout[:size]
    assert hashlib.md5(plain).hexdigest().encode() == metadata[56:88]
    return plain


def verify(original, patched):
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(patched) as after:
        assert before.testzip() is None
        assert after.testzip() is None, "ZIP CRC must match content after signing"
        dex_names = ["classes.dex", *[f"classes{i}.dex" for i in range(2, 6)]]
        assert sorted(n for n in after.namelist() if re.fullmatch(r"classes\d*\.dex", n)) == dex_names
        assert len(after.namelist()) == len(set(after.namelist())), "Duplicate ZIP entries"
        changed = []
        for name in before.namelist():
            if name.startswith("META-INF/"):
                continue
            if before.read(name) != after.read(name):
                changed.append(name)
        assert set(changed) == {"classes.dex", "classes5.dex", "assets/m7a", "assets/agconfig"}, changed
        additions = set(after.namelist()) - set(before.namelist())
        assert {n for n in additions if not n.startswith("META-INF/")} == {"lib/armeabi-v7a/libconscrypt_jni.so"}
        dex = {}
        for name in dex_names:
            data = after.read(name)
            assert data[:8] == b"dex\n035\0"
            assert struct.unpack_from("<I", data, 32)[0] == len(data)
            assert hashlib.sha1(data[32:]).digest() == data[12:32]
            assert zlib.adler32(data[12:]) == struct.unpack_from("<I", data, 8)[0]
            dex[name] = {"bytes": len(data), "crc32": f"{zlib.crc32(data):08x}",
                         "classes": struct.unpack_from("<I", data, 96)[0],
                         "sha256": hashlib.sha256(data).hexdigest()}
        old_module = decode(before.read("assets/m7a"))
        module = decode(after.read("assets/m7a"))
        match = re.search(rb"gc1:([\x00-\x0f]{128});", module)
        assert match is not None
        stored = bytes((a << 4) | b for a, b in zip(match[1][::2], match[1][1::2])).decode()
        expected = "".join(dex[name]["crc32"] for name in dex_names) + "41424344" * 3
        assert stored == expected
        allowed = set(range(0x1d09be, 0x1d09c2)) | set(range(0xe8c30, 0xe8c34)) | set(range(match.start(1), match.end(1)))
        assert len(module) == len(old_module)
        assert all(i in allowed for i, (a, b) in enumerate(zip(old_module, module)) if a != b)
        config = decode(after.read("assets/agconfig"))
        assert b"110:" + expected.encode() + b";" in config
        assert b"141:" + hashlib.sha256(after.read("assets/m7a")).hexdigest().encode() + b";" in config
        return {"zip_crc_valid": True, "dex_count": 5, "dex": dex,
                "changed_original_entries": sorted(changed), "gc1_and_config_110_match": True,
                "config_141_matches": True, "module_changes_limited": True,
                "apk_sha256": hashlib.sha256(Path(patched).read_bytes()).hexdigest()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("patched", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.original, args.patched), indent=2))
