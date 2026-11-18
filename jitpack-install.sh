#!/usr/bin/env bash
set -euo pipefail
repository="${1:?Pass the component repository name}"
[[ "$repository" =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo 'Invalid repository name' >&2; exit 1; }
: "${VERSION:?JitPack must provide an immutable tag}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$ ]] || { echo 'Invalid immutable version' >&2; exit 1; }
commit="$(git -c safe.directory="$PWD" rev-parse --verify HEAD)"
tree="$(git -c safe.directory="$PWD" rev-parse --verify 'HEAD^{tree}')"
destinations=("$HOME/.m2/repository" "$PWD/build/release-maven")
if [[ $# == 2 ]]; then destinations=("$2"); elif [[ $# != 1 ]]; then exit 2; fi
archive="${repository}-maven.tar.gz"
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
release="https://github.com/gycrosskit/${repository}/releases/download/${VERSION}"
for name in "$archive" source-receipt.json SHA256SUMS; do
  maximum=1048576
  [[ "$name" != "$archive" ]] || maximum=536870912
  curl -fL --retry 3 --connect-timeout 30 --max-time 300 --max-filesize "$maximum" -o "$staging/$name" "$release/$name"
done
# 同次受管 Release 的来源和摘要绑定冻结原件，不重编或沿用旧源码摘要。
python3 -I - "$repository" "$VERSION" "$commit" "$tree" "$staging" "${destinations[@]}" <<'VERIFY'
import hashlib, json, os, re, sys, tarfile
from pathlib import Path, PurePosixPath
repository, version, commit, tree, staging, *destinations = sys.argv[1:]
staging = Path(staging)
def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate receipt field")
        result[key] = value
    return result
raw = (staging / "source-receipt.json").read_bytes()
assert len(raw) <= 1048576, "Receipt exceeds bound"
receipt = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
assert type(receipt) is dict and type(receipt.get("version")) is int and receipt["version"] == 1, "Invalid receipt"
assert receipt.get("component") == repository and receipt.get("releaseVersion") == version, "Release identity mismatch"
repo = receipt.get("repository")
assert type(repo) is dict and repo.get("identity") == "gycrosskit/" + repository, "Repository mismatch"
assert type(repo.get("id")) is int and repo["id"] > 0, "Invalid repository ID"
assert receipt.get("source") == {"sha": commit, "tree": tree}, "Tag source mismatch"
assert all(re.fullmatch("[a-f0-9]{40}", receipt.get(key, "")) for key in ("ciCommit", "helpersCommit")), "Invalid CI identity"
build = receipt.get("build")
assert type(build) is dict and build.get("kind") == "independent", "Invalid producer"
assert re.fullmatch("[a-z][a-z0-9-]{0,63}", build.get("producer", "")) and re.fullmatch("[a-f0-9]{32}", build.get("runId", "")), "Invalid producer identity"
name = repository + "-maven.tar.gz"
item = receipt.get("archive")
assert type(item) is dict and set(item) == {"name", "sha256", "size"} and item["name"] == name, "Archive identity mismatch"
assert type(item["sha256"]) is str and re.fullmatch("[a-f0-9]{64}", item["sha256"]), "Invalid archive digest"
assert type(item["size"]) is int and 0 < item["size"] <= 536870912, "Invalid archive size"
assert (staging / "SHA256SUMS").read_bytes() == (item["sha256"] + "  " + name + "\n").encode(), "Checksum sidecar mismatch"
path = staging / name
assert path.stat().st_size == item["size"], "Archive size mismatch"
digest = hashlib.sha256()
with path.open("rb") as stream:
    for block in iter(lambda: stream.read(1048576), b""):
        digest.update(block)
assert digest.hexdigest() == item["sha256"], "Archive digest mismatch"
with tarfile.open(str(path), "r:gz") as bundle:
    members, names, expanded = [], set(), 0
    for member in bundle:
        relative = PurePosixPath(member.name)
        assert relative.parts and not relative.is_absolute() and ".." not in relative.parts and "\\" not in member.name, "Unsafe archive path"
        assert member.isfile() or member.isdir(), "Archive links and special files forbidden"
        normalized = str(relative)
        assert normalized not in names and len(names) < 20000, "Duplicate or excessive archive members"
        names.add(normalized)
        expanded += member.size
        assert 0 <= member.size and expanded <= 2147483648, "Expanded archive exceeds bound"
        members.append(member)
    assert members, "Empty archive"
    for destination in destinations:
        root = Path(os.path.realpath(destination))
        root.mkdir(parents=True, exist_ok=True)
        for member in members:
            assert os.path.commonpath((str(root), os.path.realpath(str(root / member.name)))) == str(root), "Archive escapes destination"
        bundle.extractall(str(root), members=members)
VERIFY
