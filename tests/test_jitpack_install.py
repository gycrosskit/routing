"""本地验证 JitPack 实际来源/摘要校验；不联网、不运行 SDK。"""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
VERIFY = (ROOT / 'jitpack-install.sh').read_text().split("<<'VERIFY'\n", 1)[1].split("\nVERIFY", 1)[0]


class JitPackInstallTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.staging = self.root / "release"
        self.staging.mkdir()
        self.repository = 'routing'
        self.name = self.repository + "-maven.tar.gz"
        self.receipt = dict(version=1, component=self.repository,
            repository=dict(identity="gycrosskit/" + self.repository, id=1),
            releaseVersion="1.2.3", source=dict(sha="a" * 40, tree="b" * 40),
            ciCommit="c" * 40, helpersCommit="d" * 40,
            build=dict(kind="independent", producer="worker-macos", runId="e" * 32))
        self.archive()

    def archive(self, members=None):
        with tarfile.open(str(self.staging / self.name), "w:gz") as bundle:
            for name, link in members or [("com/github/gycrosskit/probe/1.2.3/probe.pom", None)]:
                item = tarfile.TarInfo(name)
                payload = b"verified artifact"
                if link is None:
                    item.size = len(payload)
                    bundle.addfile(item, io.BytesIO(payload))
                else:
                    item.type = tarfile.SYMTYPE
                    item.linkname = link
                    bundle.addfile(item)
        raw = (self.staging / self.name).read_bytes()
        self.receipt["archive"] = dict(name=self.name, size=len(raw), sha256=hashlib.sha256(raw).hexdigest())

    def run_verify(self, *, raw_receipt=None, checksum=None, destinations=None):
        raw = json.dumps(self.receipt).encode("utf-8") if raw_receipt is None else raw_receipt
        (self.staging / "source-receipt.json").write_bytes(raw.encode("utf-8") if isinstance(raw, str) else raw)
        (self.staging / "SHA256SUMS").write_bytes(
            (self.receipt["archive"]["sha256"] + "  " + self.name + "\n").encode()
            if checksum is None else checksum)
        return subprocess.run([sys.executable, "-c", VERIFY, self.repository, "1.2.3",
            "a" * 40, "b" * 40, str(self.staging), *[str(path) for path in (destinations or (self.root / "m2", self.root / "build"))]],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)

    def test_matching_release_extracts_frozen_bytes_to_both_destinations(self):
        result = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        for destination in ("m2", "build"):
            self.assertEqual((self.root / destination / "com/github/gycrosskit/probe/1.2.3/probe.pom").read_bytes(),
                             b"verified artifact")

    def test_missing_nested_destinations_extract_valid_archive(self):
        destinations = (self.root / "missing/one/m2", self.root / "missing/two/build")
        result = self.run_verify(destinations=destinations)
        self.assertEqual(result.returncode, 0, result.stderr)
        for destination in destinations:
            self.assertEqual((destination / "com/github/gycrosskit/probe/1.2.3/probe.pom").read_bytes(),
                             b"verified artifact")

    def test_existing_destination_symlink_cannot_escape(self):
        outside = self.root / "outside"
        outside.mkdir()
        destination = self.root / "m2"
        destination.mkdir()
        (destination / "com").symlink_to(outside, target_is_directory=True)
        result = self.run_verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Archive escapes destination", result.stderr)
        self.assertEqual(list(outside.iterdir()), [])
        self.assertFalse((self.root / "build").exists())

    def test_invalid_utf8_receipt_fails_before_extract(self):
        result = self.run_verify(raw_receipt=b"{\xff}")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("UnicodeDecodeError", result.stderr)
        self.assertFalse((self.root / "m2").exists())
        self.assertFalse((self.root / "build").exists())

    def test_wrong_identity_hash_or_size_fails_before_extract(self):
        original = json.dumps(self.receipt)
        mutations = [
            lambda r: r.update(component="foreign"),
            lambda r: r.update(releaseVersion="9.9.9"),
            lambda r: r["repository"].update(identity="foreign/" + self.repository),
            lambda r: r["source"].update(sha="f" * 40),
            lambda r: r["source"].update(tree="f" * 40),
            lambda r: r["archive"].update(size=r["archive"]["size"] + 1),
            lambda r: r["archive"].update(sha256="0" * 64),
            lambda r: r["build"].update(kind="jenkins"),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.receipt = json.loads(original)
                mutate(self.receipt)
                self.assertNotEqual(self.run_verify().returncode, 0)
                self.assertFalse((self.root / "m2").exists())

    def test_duplicate_json_and_extra_checksum_lines_are_rejected(self):
        raw = json.dumps(self.receipt)
        self.assertNotEqual(self.run_verify(raw_receipt=raw[:-1] + ', "version": 1}').returncode, 0)
        self.assertNotEqual(self.run_verify(checksum=b"unexpected\n").returncode, 0)
        self.assertFalse((self.root / "m2").exists())

    def test_archive_paths_links_and_duplicate_members_are_rejected(self):
        for members in [[("../escape", None)], [("/escape", None)], [("link", "../../escape")],
                        [("same", None), ("./same", None)]]:
            with self.subTest(members=members):
                self.archive(members)
                self.assertNotEqual(self.run_verify().returncode, 0)
                self.assertFalse((self.root / "m2").exists())


if __name__ == "__main__":
    unittest.main()
