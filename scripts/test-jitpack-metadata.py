"""使用真实 ZIP/metadata 文件验证发布裁剪，不需要 Gradle 或厂商 SDK。"""
import hashlib
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import subprocess
import sys
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("metadata", Path(__file__).resolve().with_name("jitpack-metadata.py"))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class PublicationContracts(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.native = self.root / "native.module"
        self.shared = self.root / "shared.module"
        self.resource_name = "iosArm64ResourcesElements-published"
        self.api = {"name": "iosArm64ApiElements-published", "files": [{"url": "main.klib"}]}
        self.resource = {"name": self.resource_name, "files": [{"url": "resources.zip"}]}
        with ZipFile(self.root / "resources.zip", "w") as zipped:
            zipped.writestr("composeResources/", b"")
        self.write(self.native, [self.api, self.resource])
        self.write(self.shared, [{"name": self.resource_name, "available-at": {"url": "native.module"}}, {"name": "metadataApiElements"}, {"name": "metadataSourcesElements"}])

    def write(self, path, variants):
        path.write_text(json.dumps({"variants": variants}))

    def variants(self, path):
        return json.loads(path.read_text())["variants"]

    def test_empty_resource_and_forwarding_removed_api_preserved(self):
        self.assertEqual(metadata.normalize([self.native, self.shared]), 2)
        self.assertEqual(self.variants(self.native), [self.api])
        self.assertEqual(self.variants(self.shared), [{"name": "metadataApiElements"}])
        self.assertEqual(metadata.normalize([self.native, self.shared]), 0)

    def test_nonempty_resource_fails_before_any_write(self):
        before = {path: path.read_bytes() for path in (self.shared, self.native)}
        with ZipFile(self.root / "resources.zip", "a") as zipped:
            zipped.writestr("composeResources/image.png", b"image")
        with self.assertRaisesRegex(ValueError, "Nonempty resources"):
            metadata.normalize([self.shared, self.native])
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_zero_byte_real_file_also_requires_resource_publication(self):
        with ZipFile(self.root / "resources.zip", "a") as zipped:
            zipped.writestr("composeResources/empty.txt", b"")
        with self.assertRaisesRegex(ValueError, "Nonempty resources"):
            metadata.normalize([self.native, self.shared])

    def test_missing_resource_target_fails_without_write(self):
        before = self.shared.read_bytes()
        with self.assertRaisesRegex(ValueError, "Missing resource metadata"):
            metadata.normalize([self.shared])
        self.assertEqual(self.shared.read_bytes(), before)

    def test_root_sources_exact_and_classified_variants_only(self):
        keep = {"name": "customSourcesElements"}
        self.write(self.native, [self.api, keep, {"name": "iosArm64SourcesElements-published"}, {"name": "iosArm64MetadataElements-published"}])
        metadata.normalize([self.native])
        self.assertEqual(self.variants(self.native), [self.api, keep])

    def test_empty_repository_fails(self):
        with TemporaryDirectory() as empty:
            result = subprocess.run([sys.executable, str(spec.origin), empty], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("No Maven module metadata", result.stderr)

    def test_all_existing_metadata_checksums_refreshed(self):
        for algorithm in ("sha1", "sha256", "sha512", "md5"):
            self.native.with_suffix(".module." + algorithm).write_text("old")
        metadata.normalize([self.native, self.shared])
        for algorithm in ("sha1", "sha256", "sha512", "md5"):
            self.assertEqual(self.native.with_suffix(".module." + algorithm).read_text(), hashlib.new(algorithm, self.native.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
