"""Inspect only PE metadata and named loader markers, without running the file."""
import argparse
import hashlib
import json
from pathlib import Path
import struct


def inspect(path):
    data = path.read_bytes()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[:2] != b"MZ" or data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("Selected file is not PE")
    machine, sections = struct.unpack_from("<HH", data, pe + 4)
    optional_size = struct.unpack_from("<H", data, pe + 20)[0]
    optional = pe + 24
    magic = struct.unpack_from("<H", data, optional)[0]
    entry = struct.unpack_from("<I", data, optional + 16)[0]
    image = struct.unpack_from("<I" if magic == 0x10B else "<Q",
                               data, optional + (28 if magic == 0x10B else 24))[0]
    table = optional + optional_size
    blocks = []
    for index in range(sections):
        at = table + 40 * index
        name = data[at:at + 8].split(b"\0")[0].decode("ascii", errors="replace")
        virtual_size, rva, raw_size, offset = struct.unpack_from("<IIII", data, at + 8)
        flags = struct.unpack_from("<I", data, at + 36)[0]
        if offset + raw_size > len(data):
            raise ValueError("Section outside selected file")
        blocks.append({"name": name, "rva": hex(rva), "virtualBytes": virtual_size,
                       "fileOffset": offset, "fileBytes": raw_size, "flags": hex(flags),
                       "executable": bool(flags & 0x20000000)})
    markers = []
    for text in ["app.asar", "main.jsc", "app.asar.bzlmanifest",
                 "bzl-aes-256-gcm-v2", "ElectronMain", "createCachedData"]:
        for encoding in ["ascii", "utf-16le"]:
            needle = text.encode(encoding)
            start = 0
            while True:
                start = data.find(needle, start)
                if start < 0:
                    break
                block = next((b for b in blocks
                              if b["fileOffset"] <= start < b["fileOffset"] + b["fileBytes"]), None)
                markers.append({"marker": text, "encoding": encoding, "fileOffset": start,
                                "address": hex(image + int(block["rva"], 16) + start - block["fileOffset"])
                                if block else None})
                start += len(needle)
    references = []
    if machine == 0x14C:
        for marker in markers:
            if not marker["address"] or marker["marker"] not in ("app.asar", "main.jsc", "bzl-aes-256-gcm-v2"):
                continue
            candidates = []
            needle = struct.pack("<I", int(marker["address"], 16))
            for block in blocks:
                if not block["executable"]:
                    continue
                start, end = block["fileOffset"], block["fileOffset"] + block["fileBytes"]
                delta = image + int(block["rva"], 16) - start
                cursor = start
                while len(candidates) < 32:
                    cursor = data.find(needle, cursor, end)
                    if cursor < 0:
                        break
                    # Compiler-specific heuristic only. Ghidra must validate
                    # instruction boundaries and the containing function.
                    prologue = max(data.rfind(pattern, max(start, cursor - 8192), cursor)
                                   for pattern in (b"\x55\x89\xe5", b"\x55\x8b\xec"))
                    candidates.append({"operandAddress": hex(cursor + delta),
                                       "candidateFunction": hex(prologue + delta) if prologue >= 0 else None,
                                       "prologueDistance": cursor - prologue if prologue >= 0 else None})
                    cursor += 4
            references.append({"marker": marker["marker"], "encoding": marker["encoding"],
                               "address": marker["address"], "unverifiedOperandCandidates": candidates})
    return {"path": str(path), "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data), "machine": hex(machine), "architecture":
            "x86" if machine == 0x14C else "x64" if machine == 0x8664 else "unknown",
            "optionalHeader": hex(magic), "imageBase": hex(image), "entryRva": hex(entry),
            "entryAddress": hex(image + entry), "sections": blocks, "loaderMarkers": markers,
            "loaderReferences": references,
            "targetExecuted": False, "encryptedSourceRecovered": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    options = parser.parse_args()
    result = inspect(options.path.resolve())
    options.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"architecture": result["architecture"], "entry": result["entryAddress"],
                      "loaderMarkerCount": len(result["loaderMarkers"])}))
