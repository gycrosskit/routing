"""验证归档入口的准入、命令顺序和实际归档字节，不运行 SDK。"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

REPOSITORY = "routing"
SCRIPT = Path(__file__).with_name("archive-prebuilt.sh")

class ArchiveTest(unittest.TestCase):
    def test_archive_and_guards(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "scripts").mkdir()
            shutil.copy2(SCRIPT, root / "scripts/archive-prebuilt.sh")
            (root / "gradlew").write_text('#!/usr/bin/env bash\nset -euo pipefail\n[[ "$*" == \'--no-daemon --max-workers=1 -Dorg.gradle.parallel=false publishAllPublicationsToStagingRepository\' ]]\nmkdir -p build/maven/com/github/gycrosskit/routing\nprintf \'candidate bytes\' > build/maven/com/github/gycrosskit/routing/payload.txt\n')
            (root / "scripts/jitpack-metadata.py").write_text("from pathlib import Path; import sys; assert Path(sys.argv[1], 'payload.txt').is_file()\n")
            (root / "scripts/check-maven.py").write_text("from pathlib import Path; import sys; assert sys.argv[2] == 'com.github.gycrosskit.routing'; assert sys.argv[3] == '0.1.0-rc.1'; assert Path(sys.argv[1]).is_dir()\n")

            command = ["bash", "scripts/archive-prebuilt.sh"]
            env = dict(os.environ)
            env.pop("VERSION", None)
            self.assertNotEqual(0, subprocess.run(command, cwd=root, env=env, capture_output=True).returncode)
            env["VERSION"] = "bad/tag"
            self.assertNotEqual(0, subprocess.run(command, cwd=root, env=env, capture_output=True).returncode)
            env["VERSION"] = "0.1.0-rc.1"
            result = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            archive = root / f"build/prebuilt/{REPOSITORY}-maven.tar.gz"
            with tarfile.open(archive) as tar:
                member = tar.extractfile(f"com/github/gycrosskit/{REPOSITORY}/payload.txt")
                self.assertEqual(b"candidate bytes", member.read())
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            self.assertEqual(digest, archive.with_suffix(".gz.sha256").read_text().split()[0])
            self.assertNotEqual(0, subprocess.run(command, cwd=root, env=env, capture_output=True).returncode)
            self.assertEqual(digest, hashlib.sha256(archive.read_bytes()).hexdigest())

if __name__ == "__main__":
    unittest.main()
