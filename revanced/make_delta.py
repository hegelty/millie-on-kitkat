#!/usr/bin/env python3
"""Create a bounded copy/literal delta. No complete original DEX is distributed."""
import argparse
import difflib
import gzip
import hashlib
import struct
from pathlib import Path


def make_delta(original, modified):
    # Matching fixed blocks avoids quadratic byte-level matching on DEX files.
    block = 16
    before = [original[i:i + block] for i in range(0, len(original), block)]
    after = [modified[i:i + block] for i in range(0, len(modified), block)]
    result = bytearray(b"MDLT01")
    result += hashlib.sha256(original).digest() + hashlib.sha256(modified).digest()
    result += struct.pack(">II", len(original), len(modified))
    cursor = 0
    for match in difflib.SequenceMatcher(None, before, after, autojunk=True).get_matching_blocks():
        start = min(match.b * block, len(modified))
        if start > cursor:
            literal = modified[cursor:start]
            result += b"\x01" + struct.pack(">I", len(literal)) + literal
        count = min(match.size * block, len(modified) - start)
        if count:
            result += b"\x00" + struct.pack(">II", match.a * block, count)
        cursor = start + count
    result += b"\xff"
    return gzip.compress(result, mtime=0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("modified", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_bytes(make_delta(args.original.read_bytes(), args.modified.read_bytes()))
    print(f"{args.output}: {args.output.stat().st_size} bytes")
