"""Run the actual checker against complete and deliberately damaged staging."""
import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile
import tempfile
import unittest
import contextlib
import runpy
from unittest.mock import patch

GROUP = 'example.component'
VERSION = '1.0.0'


def checksums(path):
    for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
        path.with_name(path.name + '.' + algorithm).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())


def stage(root):
    for name, native in (('demo', False), ('demo-iosarm64', True)):
        folder = root / GROUP.replace('.', '/') / name / VERSION
        folder.mkdir(parents=True)
        artifact = folder / (name + '-' + VERSION + ('.klib' if native else '.jar'))
        artifact.write_bytes(b'actual fixture artifact')
        entry = {'name': artifact.name, 'url': artifact.name, 'size': artifact.stat().st_size}
        entry.update({algorithm: hashlib.new(algorithm, artifact.read_bytes()).hexdigest() for algorithm in ('md5', 'sha1', 'sha256', 'sha512')})
        variants = [{'name': 'iosArm64ApiElements-published' if native else 'metadataApiElements', 'attributes': {'org.jetbrains.kotlin.native.target': 'ios_arm64'} if native else {}, 'files': [entry]}]
        if not native:
            variants.append({'name': 'iosArm64ApiElements-published', 'available-at': {'group': GROUP, 'module': 'demo-iosarm64', 'version': VERSION, 'url': '../../demo-iosarm64/1.0.0/demo-iosarm64-1.0.0.module'}})
        module = folder / (name + '-' + VERSION + '.module')
        module.write_text(json.dumps({'component': {'group': GROUP, 'module': name, 'version': VERSION}, 'variants': variants}))
        pom = module.with_suffix('.pom')
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>' + GROUP + '</groupId><artifactId>' + name + '</artifactId><version>' + VERSION + '</version><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution></license></licenses></project>')
        for path in (artifact, module, pom): checksums(path)
    return root / GROUP.replace('.', '/') / 'demo' / VERSION / 'demo-1.0.0.module'


class CheckerTest(unittest.TestCase):
    def test_shared_artifact_digests_are_read_once_and_each_variant_is_checked(self):
        checker = Path(__file__).resolve().with_name('check-maven.py')
        for conflict in (False, True):
            with self.subTest(conflict=conflict), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                module = stage(root)
                metadata = json.loads(module.read_text())
                duplicate = copy.deepcopy(metadata['variants'][0])
                duplicate['name'] = 'metadataRuntimeElements'
                if conflict:
                    duplicate['files'][0]['sha1'] = 'incorrect'
                metadata['variants'].append(duplicate)
                module.write_text(json.dumps(metadata)); checksums(module)
                reads = {}
                read_bytes = Path.read_bytes

                def counted_read(path):
                    reads[path] = reads.get(path, 0) + 1
                    return read_bytes(path)

                argv = [str(checker), str(root), GROUP, VERSION, 'demo', 'ios_arm64', '--jvm-only']
                with patch.object(sys, 'argv', argv), patch.object(Path, 'read_bytes', counted_read), contextlib.redirect_stdout(io.StringIO()):
                    if conflict:
                        with self.assertRaises(AssertionError):
                            runpy.run_path(str(checker), run_name='__main__')
                    else:
                        runpy.run_path(str(checker), run_name='__main__')
                self.assertTrue(reads)
                self.assertTrue(all(count == 1 for count in reads.values()), reads)

    def test_complete_and_damaged_publications(self):
        checker = Path(__file__).resolve().with_name('check-maven.py')
        for damage in ('none', 'missing_declared_hash', 'wrong_artifact', 'missing_module_sidecar', 'missing_artifact_sidecar', 'foreign_redirect', 'dangling_variant', 'unexpected_publication', 'missing_license'):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                module = stage(root)
                metadata = json.loads(module.read_text())
                artifact = next(module.parent.glob('*.jar'))
                if damage == 'missing_declared_hash': del metadata['variants'][0]['files'][0]['sha512']
                if damage == 'wrong_artifact': artifact.write_bytes(b'corrupted'); checksums(artifact)
                if damage == 'missing_module_sidecar': module.with_name(module.name + '.sha512').unlink()
                if damage == 'missing_artifact_sidecar': artifact.with_name(artifact.name + '.sha1').unlink()
                if damage == 'foreign_redirect': metadata['variants'][1]['available-at']['group'] = 'foreign.group'
                if damage == 'dangling_variant': metadata['variants'][1]['name'] = 'missingApiElements-published'
                if damage == 'unexpected_publication':
                    clone = copy.deepcopy(metadata); clone['component']['module'] = 'extra'
                    folder = module.parent.parent.parent / 'extra' / VERSION; folder.mkdir(parents=True)
                    extra_artifact = folder / artifact.name; extra_artifact.write_bytes(artifact.read_bytes()); checksums(extra_artifact)
                    extra = folder / 'extra-1.0.0.module'; extra.write_text(json.dumps(clone)); checksums(extra)
                    pom = extra.with_suffix('.pom'); pom.write_text(module.with_suffix('.pom').read_text().replace('<artifactId>demo</artifactId>', '<artifactId>extra</artifactId>')); checksums(pom)
                if damage == 'missing_license':
                    pom = module.with_suffix('.pom'); text = pom.read_text(); a = text.index('<licenses>'); b = text.index('</licenses>') + len('</licenses>'); pom.write_text(text[:a] + text[b:]); checksums(pom)
                if damage not in ('missing_module_sidecar',): module.write_text(json.dumps(metadata)); checksums(module)
                result = subprocess.run([sys.executable, str(checker), str(root), GROUP, VERSION, 'demo', 'ios_arm64', 'demo,demo-iosarm64', '--jvm-only'], text=True, capture_output=True)
                self.assertEqual(result.returncode == 0, damage == 'none', result.stdout + result.stderr)
                if damage == 'unexpected_publication': self.assertIn('Missing or unexpected publications', result.stderr)


    def test_jvm_runtime_bytecode_limit(self):
        checker = Path(__file__).resolve().with_name('check-maven.py')
        for major, limit in ((61, True), (65, True), (65, False)):
            with self.subTest(major=major, limit=limit), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); module = stage(root)
                metadata = json.loads(module.read_text())
                artifact = next(module.parent.glob('*.jar'))
                with zipfile.ZipFile(artifact, 'w') as jar:
                    jar.writestr('Example.class', b'\xca\xfe\xba\xbe\x00\x00' + major.to_bytes(2, 'big'))
                variant = metadata['variants'][0]
                variant['attributes'] = {'org.jetbrains.kotlin.platform.type': 'jvm'}
                entry = variant['files'][0]; entry['size'] = artifact.stat().st_size
                entry.update({algorithm: hashlib.new(algorithm, artifact.read_bytes()).hexdigest() for algorithm in ('md5', 'sha1', 'sha256', 'sha512')})
                module.write_text(json.dumps(metadata)); checksums(module); checksums(artifact)
                command = [sys.executable, str(checker), str(root), GROUP, VERSION, 'demo', 'ios_arm64', '--jvm-only']
                if limit: command += ['--max-jvm-major', '61']
                result = subprocess.run(command, text=True, capture_output=True)
                self.assertEqual(result.returncode == 0, not limit or major == 61, result.stdout + result.stderr)
                if limit and major > 61: self.assertIn('JVM bytecode exceeds major 61', result.stderr)


    def test_android_runtime_bytecode_limit(self):
        checker = Path(__file__).resolve().with_name('check-maven.py')
        cases = ((61, None, True, False), (65, None, True, False), (61, 61, True, False), (61, 65, True, False), (65, 65, False, False), (61, None, True, True))
        for major, library_major, limit, corrupt in cases:
            with self.subTest(major=major, library_major=library_major, limit=limit, corrupt=corrupt), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); module = stage(root)
                metadata = json.loads(module.read_text())
                artifact = next(module.parent.glob('*.jar')).with_suffix('.aar')
                with zipfile.ZipFile(artifact, 'w') as aar:
                    for name, class_major in [('classes.jar', major)] + ([('libs/dependency.jar', library_major)] if library_major else []):
                        content = io.BytesIO()
                        with zipfile.ZipFile(content, 'w') as jar:
                            jar.writestr('Example.class', b'\xca\xfe\xba\xbe\x00\x00' + class_major.to_bytes(2, 'big'))
                        jar_bytes = content.getvalue()
                        if corrupt:
                            damaged = bytearray(jar_bytes)
                            damaged[jar_bytes.index(b'\xca\xfe\xba\xbe') + 4] ^= 1
                            jar_bytes = bytes(damaged)
                        aar.writestr(name, jar_bytes)
                variant = metadata['variants'][0]
                variant['attributes'] = {'org.jetbrains.kotlin.platform.type': 'androidJvm'}
                entry = variant['files'][0]
                entry.update({'name': artifact.name, 'url': artifact.name, 'size': artifact.stat().st_size})
                entry.update({algorithm: hashlib.new(algorithm, artifact.read_bytes()).hexdigest() for algorithm in ('md5', 'sha1', 'sha256', 'sha512')})
                module.write_text(json.dumps(metadata)); checksums(module); checksums(artifact)
                command = [sys.executable, str(checker), str(root), GROUP, VERSION, 'demo', 'ios_arm64']
                if limit: command += ['--max-jvm-major', '61']
                result = subprocess.run(command, text=True, capture_output=True)
                accepted = not limit or (major == 61 and library_major in (None, 61) and not corrupt)
                self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)
                if not accepted: self.assertIn('Corrupt ZIP entry' if corrupt else 'JVM bytecode exceeds major 61', result.stderr)


if __name__ == '__main__': unittest.main()
