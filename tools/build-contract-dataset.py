#!/usr/bin/env python3
"""Build the contract dataset shadersmith ships.

One row per Minecraft version, describing what a resource pack's shaders must look like for that
version: which files the client reads, which uniform blocks exist with their members *in order*
(std140 is positional, so order is the contract), which GLSL version and include directive are
expected, and which post-effect schema era applies.

Every fact is extracted from the vanilla assets published by misode/mcmeta under its per-version
`<id>-assets` tags. Nothing from Mojang is copied into the dataset - only structural facts: file
names, block names, member names and types. Re-run this to add or refresh versions:

    python3 tools/build-contract-dataset.py --append 26.3 --out src/main/resources/contract-dataset.json
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys
import urllib.request
from datetime import datetime, timezone

RAW = "https://raw.githubusercontent.com/misode/mcmeta/{ref}/{path}"
API_CONTENTS = "https://api.github.com/repos/misode/mcmeta/contents/{path}?ref={ref}"
VERSIONS_URL = "https://raw.githubusercontent.com/misode/mcmeta/summary/versions/data.json"
DEFAULT_VERSIONS = [
    "1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.9",
    "1.21.10", "1.21.11", "26.1", "26.2", "26.3-pre-1", "26.3",
]

BLOCK = re.compile(
    r"layout\s*\(\s*std140\s*\)\s*uniform\s+(?P<name>\w+)\s*\{(?P<body>[^}]*)\}",
    re.MULTILINE,
)
MEMBER = re.compile(r"^\s*(?P<type>[\w]+)\s+(?P<member>\w+)\s*;")
VERSION_LINE = re.compile(r"^#version\s+(\d+)", re.MULTILINE)

# Files outside include/ that declare a block of their own.
EXTRA_BLOCK_FILES = ["clouds.vsh", "lightmap.fsh", "animate_sprite.vsh", "terrain.vsh", "gui.vsh"]


def _github_headers() -> dict[str, str]:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "shadersmith-dataset-builder",
    }
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        token_file = pathlib.Path.home() / ".config" / "github_token"
        if token_file.is_file():
            token = token_file.read_text(encoding="utf-8").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def listing(path: str, ref: str) -> list[str]:
    """Names in one directory of the assets tree, or [] when the directory does not exist."""
    req = urllib.request.Request(API_CONTENTS.format(path=path, ref=ref), headers=_github_headers())
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if not isinstance(payload, list):
            return []
        return [entry["name"] for entry in payload if isinstance(entry, dict) and "name" in entry]
    except Exception:
        return []


def fetch(path: str, ref: str) -> str | None:
    req = urllib.request.Request(
        RAW.format(ref=ref, path=path),
        headers={"User-Agent": "shadersmith-dataset-builder"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.read().decode("utf-8", "replace")
    except Exception:
        return None


def blocks_in(text: str) -> dict[str, list[dict[str, str]]]:
    found = {}
    for match in BLOCK.finditer(text):
        members = []
        for line in match.group("body").splitlines():
            member = MEMBER.match(line)
            if member:
                members.append({"type": member.group("type"), "name": member.group("member")})
        if members:
            found[match.group("name")] = members
    return found


def post_effect_schema(version: str, has_post_effect_dir: bool, sample: str | None) -> str:
    """Which shape a post_effect pass takes. Named eras, decided by what the files show."""
    if not has_post_effect_dir:
        return "legacy-shaders-post"
    if sample is None:
        return "unknown"
    if '"program"' in sample:
        return "program-ref"
    if re.search(r'"uniforms"\s*:\s*\{', sample):
        return "uniform-blocks"
    return "uniform-list"


def row(version: str, formats: dict[str, dict]) -> dict:
    ref = f"{version}-assets"
    core = listing("assets/minecraft/shaders/core", ref)
    include = listing("assets/minecraft/shaders/include", ref)
    post = listing("assets/minecraft/shaders/post", ref)
    post_effect = listing("assets/minecraft/post_effect", ref)

    uniform_blocks: dict[str, list[dict[str, str]]] = {}
    includes_carry_version = False
    directive = None
    glsl_version = None

    for name in include:
        if not name.endswith(".glsl"):
            continue
        text = fetch(f"assets/minecraft/shaders/include/{name}", ref)
        if text is None:
            continue
        if VERSION_LINE.search(text):
            includes_carry_version = True
        uniform_blocks.update(blocks_in(text))

    for name in EXTRA_BLOCK_FILES:
        if name not in core:
            continue
        text = fetch(f"assets/minecraft/shaders/core/{name}", ref)
        if text is None:
            continue
        uniform_blocks.update(blocks_in(text))
        found = VERSION_LINE.search(text)
        if found and glsl_version is None:
            glsl_version = int(found.group(1))
        if "#moj_import" in text:
            directive = "moj_import"
        elif "#include" in text and directive is None:
            directive = "include"

    # Fall back to a program that exists in every era for the GLSL version and the directive.
    for name in ("entity.vsh", "position_color.vsh", "rendertype_entity_cutout.vsh"):
        if glsl_version is not None and directive is not None:
            break
        if name not in core:
            continue
        text = fetch(f"assets/minecraft/shaders/core/{name}", ref)
        if text is None:
            continue
        found = VERSION_LINE.search(text)
        if found and glsl_version is None:
            glsl_version = int(found.group(1))
        if "#moj_import" in text:
            directive = "moj_import"
        elif "#include" in text and directive is None:
            directive = "include"

    # `blur.json` first: at 1.21.6 it is the one file that already carries the map-shaped uniforms.
    sample = None
    for name in sorted(post_effect):
        if name.endswith(".json"):
            sample = fetch(f"assets/minecraft/post_effect/{name}", ref)
            if sample is not None:
                break

    # The vertex program every post pass uses, which is the other thing a migration has to rewrite.
    screenquad = "minecraft:core/screenquad" if "screenquad.vsh" in core else (
        "minecraft:post/screenquad" if "screenquad.vsh" in post else "minecraft:post/blit"
    )

    meta = formats.get(version, {})
    return {
        "id": version,
        "packFormat": meta.get("resource_pack_version"),
        "packFormatMinor": meta.get("resource_pack_version_minor") or 0,
        "glslVersion": glsl_version,
        "includeDirective": directive,
        "includesCarryVersion": includes_carry_version,
        "coreProgramJson": any(name.endswith(".json") for name in core),
        "postEffectSchema": post_effect_schema(version, bool(post_effect), sample),
        "postPassVertexShader": screenquad,
        "coreFiles": sorted(core),
        "includeFiles": sorted(name for name in include if name.endswith(".glsl")),
        "postEffects": sorted(name[:-5] for name in post_effect if name.endswith(".json")),
        "postPrograms": sorted(post),
        "uniformBlocks": uniform_blocks,
    }


def load_formats(path_or_none: str | None) -> dict[str, dict]:
    if path_or_none and pathlib.Path(path_or_none).is_file():
        with open(path_or_none, encoding="utf-8") as handle:
            return {entry["id"]: entry for entry in json.load(handle)}
    req = urllib.request.Request(VERSIONS_URL, headers={"User-Agent": "shadersmith-dataset-builder"})
    with urllib.request.urlopen(req, timeout=30) as response:
        entries = json.loads(response.read().decode("utf-8"))
    return {entry["id"]: entry for entry in entries}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("versions", nargs="*", default=[], help="version ids; default is the known set")
    parser.add_argument("--append", action="store_true", help="merge/replace the given versions into existing --out")
    parser.add_argument("--out", default="src/main/resources/contract-dataset.json")
    parser.add_argument("--formats", default="", help="optional local misode/mcmeta summary/versions/data.json")
    args = parser.parse_args()

    formats = load_formats(args.formats or None)
    versions = args.versions or DEFAULT_VERSIONS

    existing_by_id: dict[str, dict] = {}
    ordered_ids: list[str] = []
    if args.append and pathlib.Path(args.out).is_file():
        with open(args.out, encoding="utf-8") as handle:
            prev = json.load(handle)
        for item in prev.get("versions", []):
            existing_by_id[item["id"]] = item
            ordered_ids.append(item["id"])

    for version in versions:
        print(f"reading {version} ...", file=sys.stderr)
        built = row(version, formats)
        existing_by_id[version] = built
        if version not in ordered_ids:
            ordered_ids.append(version)

    rows = sorted(
        (existing_by_id[vid] for vid in ordered_ids),
        key=lambda r: (r["packFormat"], r.get("packFormatMinor", 0)),
    )

    dataset = {
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "source": "vanilla assets published by misode/mcmeta, per-version <id>-assets tags",
        "versions": rows,
    }
    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(dataset, handle, indent=2)
        handle.write("\n")
    print(f"wrote {args.out} with {len(rows)} versions", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
