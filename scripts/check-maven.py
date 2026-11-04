"""检查实际发布文件与 Gradle 变体引用，避免本机源码编译掩盖损坏的 Maven 产物。"""
import hashlib
import io
import json
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('repository')
parser.add_argument('group')
parser.add_argument('version')
parser.add_argument('modules_csv')
parser.add_argument('native_targets_csv')
parser.add_argument('publications_csv', nargs='?')
parser.add_argument('--max-jvm-major', type=int, help='Maximum class-file major for runtime JVM JAR and Android AAR variants')
parser.add_argument('--jvm-only', action='store_true', help='JVM JAR rather than Android AAR publication')
parser.add_argument('--license', choices=('Apache-2.0', 'BSD-3-Clause'), default='Apache-2.0')
args = parser.parse_args()
repository = Path(args.repository).resolve()
expected_group = args.group
expected_version = args.version
expected_modules = set(args.modules_csv.split(','))
expected_targets = set(filter(None, args.native_targets_csv.split(',')))


# 缓存摘要而非二进制；不同 variant 的声明仍分别核对。
file_digests = {}


def digests(path):
    if path not in file_digests:
        content = path.read_bytes()
        file_digests[path] = {algorithm: hashlib.new(algorithm, content).hexdigest()
                              for algorithm in ('md5', 'sha1', 'sha256', 'sha512')}
    return file_digests[path]


def check_sidecars(path):
    for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
        checksum = path.with_name(path.name + '.' + algorithm)
        assert checksum.is_file(), f'Missing checksum: {checksum}'
        assert checksum.read_text().strip() == digests(path)[algorithm], checksum


def check_jvm_bytecode(source, label, require_classes=True):
    with zipfile.ZipFile(source) as jar:
        assert jar.testzip() is None, f"Corrupt ZIP entry: {label}"
        classes = [name for name in jar.namelist() if name.endswith(".class")]
        assert classes or not require_classes, f"Missing JVM classes: {label}"
        for name in classes:
            with jar.open(name) as entry_class:
                header = entry_class.read(8)
            assert len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe", f"Invalid class header: {label}!{name}"
            assert int.from_bytes(header[6:8], "big") <= args.max_jvm_major, f"JVM bytecode exceeds major {args.max_jvm_major}: {label}!{name}"


def resolve_artifact(path):
    # Maven SNAPSHOT 元数据使用逻辑版本，磁盘文件使用时间戳版本。
    if not path.parent.name.endswith("-SNAPSHOT"):
        return path
    metadata = ET.parse(path.parent / "maven-metadata.xml").getroot()
    artifact_id = metadata.findtext("artifactId")
    version = metadata.findtext("version")
    suffix = path.name.removeprefix(f"{artifact_id}-{version}")
    for snapshot in metadata.findall("./versioning/snapshotVersions/snapshotVersion"):
        classifier = snapshot.findtext("classifier")
        extension = snapshot.findtext("extension")
        expected_suffix = (f"-{classifier}" if classifier else "") + f".{extension}"
        if suffix == expected_suffix:
            return path.with_name(f"{artifact_id}-{snapshot.findtext('value')}{suffix}")
    raise AssertionError(f"No current snapshot artifact: {path}")


modules = []
for path in repository.rglob("*.module"):
    if path.parent.name.endswith("-SNAPSHOT"):
        logical = path.parent / f"{path.parent.parent.name}-{path.parent.name}.module"
        if path != resolve_artifact(logical):
            continue
    modules.append(path)
assert modules, "Maven staging repository is empty"
platforms = set()
coordinates = {}
for module in modules:
    check_sidecars(module)
    check_sidecars(module.with_suffix(".pom"))
    pom = ET.parse(module.with_suffix(".pom")).getroot()
    namespaces = {"m": "http://maven.apache.org/POM/4.0.0"}
    assert pom.findtext("m:groupId", namespaces=namespaces) == expected_group, module
    assert pom.findtext("m:version", namespaces=namespaces) == expected_version, module
    published_module = pom.findtext("m:artifactId", namespaces=namespaces)
    assert published_module == module.parent.parent.name, module
    license = pom.find("m:licenses/m:license", namespaces)
    assert license is not None, f"Missing license: {module}"
    expected_license = ("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt") if args.license == "Apache-2.0" else ("BSD 3-Clause License", "https://opensource.org/licenses/BSD-3-Clause")
    assert license.findtext("m:name", namespaces=namespaces) == expected_license[0], module
    assert license.findtext("m:url", namespaces=namespaces) == expected_license[1], module
    assert license.findtext("m:distribution", namespaces=namespaces) == "repo", module
    data = json.loads(module.read_text())
    component = data["component"]
    assert component["group"] == expected_group, component
    assert component["version"] == expected_version, component
    coordinates.setdefault(published_module, set()).add(component["version"])
    if "url" in component:
        assert resolve_artifact((module.parent / component["url"]).resolve()).is_file(), component
    for variant in data["variants"]:
        assert variant["name"] != "metadataSourcesElements", variant
        assert not variant["name"].endswith(("SourcesElements-published", "MetadataElements-published", "ResourcesElements-published")), variant
        target = variant.get("attributes", {}).get("org.jetbrains.kotlin.native.target")
        if target:
            platforms.add(target)
        if "available-at" in variant:
            redirect = variant["available-at"]
            target_module = resolve_artifact((module.parent / redirect["url"]).resolve())
            assert redirect['group'] == expected_group and redirect['version'] == expected_version, redirect
            assert target_module.is_file(), redirect
            assert target_module.parent.parent.name == redirect['module'], redirect
            target_variants = json.loads(target_module.read_text())["variants"]
            assert any(item["name"] == variant["name"] for item in target_variants), f"Dangling variant: {variant}"
        for entry in variant.get("files", []):
            artifact = resolve_artifact((module.parent / entry["url"]).resolve())
            assert artifact.is_file(), artifact
            check_sidecars(artifact)
            assert artifact.stat().st_size == entry["size"], artifact
            if args.max_jvm_major is not None and artifact.suffix == ".jar" and variant.get("attributes", {}).get("org.jetbrains.kotlin.platform.type") == "jvm":
                check_jvm_bytecode(artifact, artifact)
            if args.max_jvm_major is not None and artifact.suffix == ".aar":
                # Android unit consumers 也在 Java 17 上加载 AAR 的 classes.jar。
                with zipfile.ZipFile(artifact) as aar:
                    assert aar.testzip() is None, f"Corrupt ZIP entry: {artifact}"
                    assert "classes.jar" in aar.namelist(), f"Missing classes.jar: {artifact}"
                    for name in aar.namelist():
                        if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                            check_jvm_bytecode(io.BytesIO(aar.read(name)), f"{artifact}!{name}", require_classes=name == "classes.jar")
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                assert digests(artifact)[algorithm] == entry[algorithm], artifact

for module in modules:
    for variant in json.loads(module.read_text())["variants"]:
        for dependency in variant.get("dependencies", []):
            if dependency["module"] in coordinates:
                assert dependency["group"] == expected_group, dependency
                assert dependency["version"]["requires"] in coordinates[dependency["module"]], dependency

assert expected_targets <= platforms, platforms
assert list(repository.rglob("*.jar" if args.jvm_only else "*.aar")), "Required JVM/Android artifact is missing"
expected_coordinates = set(args.publications_csv.split(",")) if args.publications_csv else set(expected_modules)
if args.publications_csv is None:
    for module in modules:
        if module.parent.parent.name in expected_modules:
            for variant in json.loads(module.read_text())["variants"]:
                if "available-at" in variant:
                    expected_coordinates.add(variant["available-at"]["module"])
assert expected_modules <= expected_coordinates, expected_coordinates
assert expected_coordinates == coordinates.keys(), f"Missing or unexpected publications: {coordinates}"
assert len(modules) == len(expected_coordinates), "Duplicate module metadata"
print(f"Maven metadata: {len(modules)} modules; artifact hashes/sidecars, POM licenses, project dependencies and platform variants passed")
