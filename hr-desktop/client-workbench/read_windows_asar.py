"""Read authenticated resources from the user's fingerprint-pinned Windows app.

Static analysis only: no target execution, patched checks, signature stripping,
or key export. Third-party outputs must stay outside a Git checkout.
Requires Python's cryptography package.
"""
import argparse
import base64
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

FORMAT = "bzl-aes-256-gcm-v2"
MAX_HEADER = 16 * 1024 * 1024
MAX_FILE = 64 * 1024 * 1024
MAX_EXPORT = 256 * 1024 * 1024


@dataclass(frozen=True)
class BinaryProfile:
    sha256: str
    public_key_va: int
    key_part_a_va: int
    key_part_b_va: int
    kdf_prefix_va: int
    format_va: int


WINDOWS_175 = BinaryProfile(
    sha256="be47a9065167f57d43933f0d6cb1c6f764084dcc3ba88ef47ba212283d6e953f",
    public_key_va=0x067DB3A0,
    key_part_a_va=0x067DB3E9,
    key_part_b_va=0x067DB409,
    kdf_prefix_va=0x067DB429,
    format_va=0x067DB2CE,
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def frame(data):
    return struct.pack(">I", len(data)) + data


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON member")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs)


def unbase64url(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("Invalid manifest encoding")
    return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)


def canonical_path(value):
    if not isinstance(value, str) or not value or "\\" in value or "\0" in value or ":" in value:
        raise ValueError("Invalid archive resource path")
    parts = value.split("/")
    if any(part in ("", ".", "..") for part in parts) or PurePosixPath(value).is_absolute():
        raise ValueError("Invalid archive resource path")
    return value


class PinnedBinary:
    def __init__(self, path, profile=WINDOWS_175):
        self.data = Path(path).read_bytes()
        if digest(self.data) != profile.sha256:
            raise ValueError("Binary differs from the reviewed Windows input")
        if len(self.data) < 64 or self.data[:2] != b"MZ":
            raise ValueError("Not a reviewed PE32 binary")
        pe = struct.unpack_from("<I", self.data, 0x3C)[0]
        if pe + 24 > len(self.data) or self.data[pe:pe + 4] != b"PE\0\0":
            raise ValueError("Invalid PE header")
        machine, count = struct.unpack_from("<HH", self.data, pe + 4)
        optional_size = struct.unpack_from("<H", self.data, pe + 20)[0]
        optional = pe + 24
        if (machine != 0x14C or optional_size < 32
                or optional + optional_size + count * 40 > len(self.data)
                or struct.unpack_from("<H", self.data, optional)[0] != 0x10B):
            raise ValueError("Expected the reviewed PE32/x86 layout")
        self.image_base = struct.unpack_from("<I", self.data, optional + 28)[0]
        self.sections = []
        table = optional + optional_size
        for index in range(count):
            _, rva, size, offset = struct.unpack_from("<IIII", self.data, table + index * 40 + 8)
            if offset + size > len(self.data):
                raise ValueError("PE section outside binary")
            self.sections.append((rva, size, offset))
        self.profile = profile
        if self.read_va(profile.format_va, 18) != FORMAT.encode():
            raise ValueError("Reviewed archive format marker differs")
        if self.read_va(profile.kdf_prefix_va, 12) != b"bzl-asar-v2\0":
            raise ValueError("Reviewed KDF marker differs")

    def read_va(self, address, size):
        for rva, raw_size, offset in self.sections:
            relative = address - self.image_base - rva
            if 0 <= relative and relative + size <= raw_size:
                return self.data[offset + relative:offset + relative + size]
        raise ValueError("Reviewed address outside mapped PE sections")

    def public_key(self):
        return Ed25519PublicKey.from_public_bytes(self.read_va(self.profile.public_key_va, 32))

    def derive(self, archive_id, name, size):
        # Reproduce the selected loader's in-memory XOR and per-file KDF.
        # The root key is never serialized, returned, printed, or stored in Git.
        left = self.read_va(self.profile.key_part_a_va, 32)
        right = self.read_va(self.profile.key_part_b_va, 32)
        root = bytes(a ^ b for a, b in zip(left, right))
        info = self.read_va(self.profile.kdf_prefix_va, 12)
        info += frame(name.encode("utf-8")) + frame(str(size).encode("ascii"))
        return HKDF(algorithm=hashes.SHA256(), length=32, salt=archive_id, info=info).derive(root)


class WindowsArchive:
    def __init__(self, binary, archive, manifest=None):
        self.binary = binary
        self.archive = Path(archive)
        manifest = Path(manifest) if manifest else Path(str(self.archive) + ".bzlmanifest")
        if manifest.stat().st_size > 16 * 1024:
            raise ValueError("Manifest exceeds bounded range")
        envelope = strict_json(manifest.read_bytes())
        if envelope.get("version") != 2 or not isinstance(envelope.get("payload"), str):
            raise ValueError("Unsupported manifest")
        payload_bytes = envelope["payload"].encode("utf-8")
        try:
            binary.public_key().verify(unbase64url(envelope["signature"]), payload_bytes)
        except InvalidSignature:
            raise ValueError("Original manifest signature did not verify") from None
        payload = strict_json(payload_bytes)
        with self.archive.open("rb") as stream:
            prefix = stream.read(8)
            if len(prefix) != 8 or struct.unpack_from("<I", prefix)[0] != 4:
                raise ValueError("Invalid ASAR framing")
            amount = struct.unpack_from("<I", prefix, 4)[0]
            if not 8 <= amount <= MAX_HEADER or amount % 4:
                raise ValueError("ASAR header outside bounded range")
            raw = stream.read(amount)
        if len(raw) != amount:
            raise ValueError("Truncated ASAR header")
        pickle_size, json_size = struct.unpack_from("<II", raw)
        if pickle_size != amount - 4 or not 0 < json_size <= amount - 8:
            raise ValueError("Invalid ASAR pickle length")
        header_bytes = raw[8:8 + json_size]
        if hashlib.sha256(header_bytes).digest() != unbase64url(payload["headerSha256"]):
            raise ValueError("ASAR header differs from the signed manifest")
        self.header = strict_json(header_bytes)
        self.start = 8 + amount
        self.archive_bytes = self.archive.stat().st_size
        enc = self.header.get("encryption", {})
        if enc.get("format") != FORMAT or enc.get("version") != 2:
            raise ValueError("Unsupported encrypted ASAR")
        for key in ("format", "version", "keyId", "archiveId"):
            if enc.get(key) != payload.get(key):
                raise ValueError("ASAR encryption metadata differs from signed manifest")
        if not re.fullmatch(r"[0-9a-f]{32}", enc.get("archiveId", "")):
            raise ValueError("Invalid archive ID")
        if not isinstance(enc.get("keyId"), str) or not enc["keyId"]:
            raise ValueError("Invalid archive key ID")
        self.archive_id = bytes.fromhex(enc["archiveId"])
        self.key_id = enc["keyId"].encode("utf-8")
        self.entries = {}
        self._walk(self.header)
        packed = sum(n.get("encryption") == FORMAT for n in self.entries.values())
        if packed != enc.get("packedFiles"):
            raise ValueError("Encrypted resource count differs from header")

    def _walk(self, tree, prefix="", depth=0):
        files = tree.get("files")
        if depth > 64 or not isinstance(files, dict):
            raise ValueError("Invalid ASAR directory")
        for leaf, node in files.items():
            if not isinstance(leaf, str) or "/" in leaf or not isinstance(node, dict):
                raise ValueError("Invalid ASAR directory member")
            name = canonical_path(prefix + leaf)
            if "files" in node:
                self._walk(node, name + "/", depth + 1)
            else:
                self.entries[name] = node

    def read(self, name):
        canonical_path(name)
        node = self.entries[name]
        if node.get("unpacked") or "link" in node or node.get("encryption") != FORMAT:
            raise ValueError("Selected resource is not an encrypted packed file")
        size, encrypted_size = node.get("size"), node.get("encryptedSize")
        if (type(size) is not int or not 0 <= size <= MAX_FILE
                or type(encrypted_size) is not int or encrypted_size != size + 28):
            raise ValueError("Invalid encrypted resource size")
        offset = node.get("offset")
        if not isinstance(offset, str) or not re.fullmatch(r"0|[1-9][0-9]*", offset):
            raise ValueError("Invalid packed resource offset")
        offset = int(offset)
        if self.start + offset + encrypted_size > self.archive_bytes:
            raise ValueError("Resource outside archive")
        if type(node.get("executable", False)) is not bool:
            raise ValueError("Invalid executable metadata")
        aad = b"".join(frame(value) for value in (
            FORMAT.encode(), self.archive_id, self.key_id, name.encode("utf-8"),
            struct.pack(">Q", size), struct.pack(">Q", encrypted_size),
            bytes([int(node.get("executable", False))]),
        ))
        with self.archive.open("rb") as stream:
            stream.seek(self.start + offset)
            sealed = stream.read(encrypted_size)
        if len(sealed) != encrypted_size:
            raise ValueError("Truncated encrypted resource")
        key = self.binary.derive(self.archive_id, name, size)
        try:
            plain = AESGCM(key).decrypt(sealed[:12], sealed[12:], aad)
        except InvalidTag:
            raise ValueError("Resource authentication tag did not verify") from None
        integrity = node.get("integrity", {})
        block_size = integrity.get("blockSize")
        if (len(plain) != size or integrity.get("algorithm") != "SHA256"
                or digest(plain) != integrity.get("hash")
                or type(block_size) is not int or not 1 <= block_size <= MAX_FILE):
            raise ValueError("Decrypted resource integrity did not verify")
        blocks = [digest(plain[i:i + block_size]) for i in range(0, len(plain), block_size)]
        if blocks != integrity.get("blocks"):
            raise ValueError("Decrypted resource block integrity did not verify")
        return plain

    def summary(self):
        return {"binarySha256": self.binary.profile.sha256, "architecture": "x86",
                "manifestSignatureVerified": True, "headerSha256Verified": True,
                "packedFiles": self.header["encryption"]["packedFiles"],
                "totalFiles": len(self.entries), "targetExecuted": False,
                "binaryOrManifestModified": False}


def export_selected(archive, output, names):
    output = Path(output).absolute()
    # Resolve existing parent symlinks before checking containment.
    resolved = output.resolve()
    if any((parent / ".git").exists() for parent in (resolved, *resolved.parents)):
        raise ValueError("Keep third-party resources outside any Git checkout")
    if output.exists():
        raise ValueError("Choose a new output directory; existing files are not overwritten")
    selected = list(dict.fromkeys(canonical_path(name) for name in names))
    total = sum(archive.entries[name].get("size", MAX_EXPORT + 1) for name in selected)
    if total > MAX_EXPORT:
        raise ValueError("Selected resources exceed bounded export size")
    # Authenticate all selected resources before creating the output directory.
    content = [(name, archive.read(name)) for name in selected]
    output.mkdir(parents=True, mode=0o700)
    result = []
    for name, plain in content:
        target = output.joinpath(*PurePosixPath(name).parts)
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with target.open("xb") as stream:
            stream.write(plain)
        target.chmod(0o600)
        result.append({"path": name, "bytes": len(plain), "sha256": digest(plain),
                       "authenticationTagVerified": True, "integrityVerified": True})
    report = {**archive.summary(), "exported": result}
    with (output / "_authenticated-read-report.json").open("x") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", required=True, type=Path)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--file", action="append", default=[])
    parser.add_argument("--prefix", action="append", default=[])
    parser.add_argument("--output", type=Path)
    options = parser.parse_args()
    archive = WindowsArchive(PinnedBinary(options.exe), options.archive)
    selected = list(options.file)
    for prefix in options.prefix:
        canonical_path(prefix.rstrip("/"))
        selected += [name for name in archive.entries if name.startswith(prefix)]
    if selected:
        if not options.output:
            parser.error("--output is required when selecting resources")
        result = export_selected(archive, options.output, selected)
    else:
        result = {**archive.summary(), "resources": [
            {"path": name, "bytes": node.get("size"), "unpacked": bool(node.get("unpacked"))}
            for name, node in archive.entries.items()
        ]}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
