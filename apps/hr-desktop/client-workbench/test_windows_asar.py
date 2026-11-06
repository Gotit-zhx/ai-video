"""Synthetic archives only. No vendor code, vendor keys, network, or execution."""
import base64
import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from read_windows_asar import (
    BinaryProfile, FORMAT, PinnedBinary, WindowsArchive, canonical_path, digest, export_selected,
)


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def encode_field(value):
    return len(value).to_bytes(4, "big") + value


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.signer = Ed25519PrivateKey.generate()
        public = self.signer.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        # A minimal PE32 section holding synthetic test material.
        binary = bytearray(1024)
        binary[:2] = b"MZ"
        struct.pack_into("<I", binary, 0x3C, 0x80)
        binary[0x80:0x84] = b"PE\0\0"
        struct.pack_into("<HH", binary, 0x84, 0x14C, 1)
        struct.pack_into("<H", binary, 0x94, 0xE0)
        struct.pack_into("<H", binary, 0x98, 0x10B)
        struct.pack_into("<I", binary, 0x98 + 28, 0x400000)
        table = 0x98 + 0xE0
        binary[table:table + 8] = b".rdata\0\0"
        struct.pack_into("<IIII", binary, table + 8, 512, 0x1000, 512, 0x200)
        binary[0x200:0x220] = public
        part_a, part_b = bytes(range(32)), bytes(range(32, 64))
        self.root_key = bytes(a ^ b for a, b in zip(part_a, part_b))
        binary[0x220:0x240] = part_a
        binary[0x240:0x260] = part_b
        binary[0x260:0x26C] = b"bzl-asar-v2\0"
        binary[0x280:0x292] = FORMAT.encode()
        self.exe = self.directory / "synthetic.exe"
        self.exe.write_bytes(binary)
        self.profile = BinaryProfile(digest(binary), 0x401000, 0x401020, 0x401040,
                                     0x401060, 0x401080)
        self.binary = PinnedBinary(self.exe, self.profile)
        self.archive = self.directory / "app.asar"
        self.manifest = self.directory / "app.asar.bzlmanifest"
        self.aid = bytes.fromhex("0123456789abcdef0123456789abcdef")
        self.kid = b"synthetic-only"
        self.contents = {"package.json": b'{"main":"fixture.js"}',
                         "assets/fixture.js": b"/* offline fixture */\n", "empty": b""}
        self.sealed = b""
        self.tree = {"encryption": {
            "format": FORMAT, "version": 2, "archiveId": self.aid.hex(),
            "keyId": self.kid.decode(), "packedFiles": len(self.contents)}, "files": {}}
        for name, plain in self.contents.items():
            encrypted_size = len(plain) + 28
            size = len(plain)
            info = (b"bzl-asar-v2\0" + encode_field(name.encode())
                    + encode_field(str(size).encode()))
            key = HKDF(algorithm=hashes.SHA256(), length=32, salt=self.aid, info=info).derive(
                self.root_key)
            aad = b"".join(encode_field(x) for x in (
                FORMAT.encode(), self.aid, self.kid, name.encode(),
                size.to_bytes(8, "big"), encrypted_size.to_bytes(8, "big"), b"\0"))
            nonce = len(self.sealed).to_bytes(12, "big")
            sealed = nonce + AESGCM(key).encrypt(nonce, plain, aad)
            node = {"size": size, "encryptedSize": encrypted_size, "offset": str(len(self.sealed)),
                    "encryption": FORMAT, "integrity": {
                        "algorithm": "SHA256", "hash": digest(plain), "blockSize": 8,
                        "blocks": [digest(plain[i:i + 8]) for i in range(0, size, 8)]}}
            directory = self.tree
            parts = name.split("/")
            for part in parts[:-1]:
                directory = directory["files"].setdefault(part, {"files": {}})
            directory["files"][parts[-1]] = node
            self.sealed += sealed
        self.write_archive()

    def write_archive(self):
        raw = json.dumps(self.tree, separators=(",", ":")).encode()
        padded = raw + b"\0" * (-len(raw) % 4)
        packed = struct.pack("<II", 4 + len(padded), len(raw)) + padded
        self.archive.write_bytes(struct.pack("<II", 4, len(packed)) + packed + self.sealed)
        payload = {key: self.tree["encryption"][key]
                   for key in ("archiveId", "format", "keyId", "version")}
        payload["headerSha256"] = b64(bytes.fromhex(digest(raw)))
        self.write_manifest(payload)

    def write_manifest(self, payload):
        text = json.dumps(payload, separators=(",", ":"))
        self.manifest.write_text(json.dumps({
            "version": 2, "payload": text, "signature": b64(self.signer.sign(text.encode()))}))

    def reader(self):
        return WindowsArchive(self.binary, self.archive)

    def test_authenticates_multiblock_and_empty_resources(self):
        reader = self.reader()
        for name, plain in self.contents.items():
            self.assertEqual(reader.read(name), plain)
        self.assertTrue(reader.summary()["manifestSignatureVerified"])
        self.assertFalse(reader.summary()["targetExecuted"])

    def test_rejects_unreviewed_binary(self):
        with self.assertRaisesRegex(ValueError, "differs"):
            PinnedBinary(self.exe)

    def test_rejects_manifest_signature_tamper(self):
        env = json.loads(self.manifest.read_text())
        env["payload"] = env["payload"].replace("synthetic-only", "synthetic-fake")
        self.manifest.write_text(json.dumps(env))
        with self.assertRaisesRegex(ValueError, "signature"):
            self.reader()

    def test_rejects_header_tamper(self):
        data = self.archive.read_bytes().replace(b"synthetic-only", b"synthetic-fake", 1)
        self.archive.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "signed manifest"):
            self.reader()

    def test_rejects_signed_metadata_mismatch(self):
        env = json.loads(self.manifest.read_text())
        payload = json.loads(env["payload"])
        payload["keyId"] = "different"
        self.write_manifest(payload)
        with self.assertRaisesRegex(ValueError, "metadata differs"):
            self.reader()

    def test_rejects_truncated_header(self):
        self.archive.write_bytes(self.archive.read_bytes()[:20])
        with self.assertRaisesRegex(ValueError, "Truncated"):
            self.reader()

    def test_rejects_ciphertext_tamper(self):
        data = bytearray(self.archive.read_bytes())
        data[-1] ^= 1
        self.archive.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "authentication tag"):
            self.reader().read("empty")

    def test_authenticates_executable_flag(self):
        self.tree["files"]["package.json"]["executable"] = True
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "authentication tag"):
            self.reader().read("package.json")

    def test_verifies_whole_file_hash_after_valid_tag(self):
        self.tree["files"]["package.json"]["integrity"]["hash"] = "0" * 64
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "integrity"):
            self.reader().read("package.json")

    def test_verifies_block_hashes_after_valid_tag(self):
        self.tree["files"]["package.json"]["integrity"]["blocks"][0] = "0" * 64
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "block integrity"):
            self.reader().read("package.json")

    def test_rejects_range_outside_archive(self):
        self.tree["files"]["package.json"]["offset"] = str(2**32)
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "outside archive"):
            self.reader().read("package.json")

    def test_rejects_traversal_and_noncanonical_names(self):
        for name in ["../x", "/x", "x/../y", r"x\y", "C:/x", "x//y", "x/./y", "x\0"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                canonical_path(name)

    def test_never_exports_into_git_or_overwrites_output(self):
        existing = self.directory / "existing"
        existing.mkdir()
        with self.assertRaisesRegex(ValueError, "existing files"):
            export_selected(self.reader(), existing, ["package.json"])
        (existing / ".git").write_text("gitdir: fixture")
        with self.assertRaisesRegex(ValueError, "Git checkout"):
            export_selected(self.reader(), existing / "new", ["package.json"])

    def test_authenticates_all_selected_files_before_creating_output(self):
        self.tree["files"]["empty"]["integrity"]["hash"] = "0" * 64
        self.write_archive()
        output = self.directory / "output"
        with self.assertRaisesRegex(ValueError, "integrity"):
            export_selected(self.reader(), output, ["package.json", "empty"])
        self.assertFalse(output.exists())

    def test_exports_verified_resources_and_evidence(self):
        output = self.directory / "output"
        report = export_selected(self.reader(), output, self.contents)
        self.assertEqual(len(report["exported"]), 3)
        for name, plain in self.contents.items():
            self.assertEqual((output / name).read_bytes(), plain)
        self.assertTrue((output / "_authenticated-read-report.json").is_file())


if __name__ == "__main__":
    unittest.main()
