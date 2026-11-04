"""移除 JitPack 无法保持 classifier 的变体，空资源可省略，真实资源必须修复发布。"""
import hashlib
import json
import sys
from pathlib import Path
from zipfile import ZipFile


def normalize(files):
    modules = {file.resolve(): json.loads(file.read_text()) for file in files}
    removed = {file: set() for file in modules}
    # 先完成全部验证，非空资源失败时不留下部分改写的仓库。
    for file, data in modules.items():
        for variant in data["variants"]:
            name = variant["name"]
            if name == "metadataSourcesElements" or name.endswith(("SourcesElements-published", "MetadataElements-published")):
                removed[file].add(name)
            elif name.endswith("ResourcesElements-published") and "available-at" not in variant:
                entries = variant.get("files", [])
                if not entries:
                    raise ValueError(f"Resource variant has no archive: {file}: {name}")
                for entry in entries:
                    archive = (file.parent / entry["url"]).resolve()
                    if archive.suffix != ".zip":
                        raise ValueError(f"Unsupported resource archive: {archive}")
                    with ZipFile(archive) as zipped:
                        if any(not item.is_dir() for item in zipped.infolist()):
                            raise ValueError(f"Nonempty resources require a classifier-safe publication: {archive}")
                removed[file].add(name)
    for file, data in modules.items():
        for variant in data["variants"]:
            redirect = variant.get("available-at")
            if redirect and variant["name"].endswith("ResourcesElements-published"):
                target = (file.parent / redirect["url"]).resolve()
                if target not in modules:
                    raise ValueError(f"Missing resource metadata target: {target}")
                if variant["name"] in removed[target]:
                    removed[file].add(variant["name"])
    changed = 0
    for file, data in modules.items():
        if removed[file]:
            data["variants"] = [variant for variant in data["variants"] if variant["name"] not in removed[file]]
            file.write_text(json.dumps(data, indent=2))
            changed += 1
        for algorithm in ("sha1", "sha256", "sha512", "md5"):
            checksum = file.with_name(file.name + "." + algorithm)
            if checksum.exists():
                checksum.write_text(hashlib.new(algorithm, file.read_bytes()).hexdigest())
    return changed


if __name__ == "__main__":
    roots = list(map(Path, sys.argv[1:])) or [Path.home() / ".m2/repository/com/github/gycrosskit"]
    files = sorted({file for root in roots for file in root.rglob("*.module")})
    if not files:
        raise SystemExit("No Maven module metadata found")
    changed = normalize(files)
    print(f"Checked {len(files)} Maven modules; fixed {changed} JitPack metadata variants")
