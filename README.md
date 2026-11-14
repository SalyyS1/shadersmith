# shadersmith

[![build](https://github.com/SalyyS1/shadersmith/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/shadersmith/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/SalyyS1/shadersmith)](https://github.com/SalyyS1/shadersmith/releases)

The Minecraft client does not validate a resource pack's shaders against anything. It compiles them, and if
they compile, it runs them. A shader written for a previous version can compile cleanly on the next release and
read the wrong uniform, because the contract it was written against (which include directive, which GLSL
version, which members a `std140` uniform block has and in what order) changed without raising a compile error.

`shadersmith` holds that per-version contract, extracted directly from the game's own vanilla shader files
across 1.21.4 through 26.3, and checks or migrates a resource pack directory or `.zip` archive against it:

```
$ shadersmith check hud-pack/ --mc 26.3-pre-1
== hud-pack on 26.3-pre-1: 12 error(s), 2 warning(s)
   error   SM041  assets/minecraft/shaders/core/gui.vsh:3
           Uniform block DynamicTransforms does not match vanilla 26.3-pre-1. Declared: mat4 ModelViewMat,
           vec4 ColorModulator, vec3 ModelOffset, mat4 TextureMat, float LineWidth. Vanilla: mat4 ModelViewMat,
           mat4 TextureMat, vec4 ColorModulator, vec3 ModelOffset. A member that does not exist reads garbage;
           a missing one shifts every member after it.
           fix: rewrite the uniform block to the version's layout
   error   SM030  assets/minecraft/shaders/core/text.fsh:3
           #moj_import is not the include directive on 26.3-pre-1; it is #include. The file will not preprocess.
           fix: line 3 -> '#include <minecraft:fog.glsl>'
   error   SM020  assets/minecraft/shaders/include/fog.glsl:1
           An include must not declare #version on 26.3-pre-1; the directive belongs on the first line of the
           main shader only, and a second one fails compilation.
           fix: delete line 1
   ...
```

Then it rewrites what is mechanical, prints a unified diff first, preserves all non-shader pack assets (for
both directory and `.zip` inputs), and lists what still needs a human decision.

## Vanilla Shader Contract Changes Covered

Every contract entry was generated directly from vanilla shader files on both sides of each version boundary:

| Version | What changed | What happens if you miss it |
|---|---|---|
| 1.21.5 | `shaders/core/*.json` program definitions deleted; only GLSL is read | JSON is silently ignored; render-type remapping stops running |
| 1.21.5 | `shaders/post/*.json` program configs deleted; post effects name shaders directly | Silently ignored |
| 1.21.6 | Post effect `uniforms` becomes a map from block name to members | List form fails to parse |
| 1.21.9 | GLSL `#version 150` becomes `#version 330`; post passes use `minecraft:core/screenquad` | Compile error or missing vertex shader reference |
| 1.21.11 | `LineWidth` leaves `DynamicTransforms` and becomes a vertex attribute | Block compiles cleanly and reads past buffer |
| 26.3 | `#moj_import` becomes `#include` | Preprocess failure |
| 26.3 | Include files no longer carry `#version` | Compile failure on second `#version` directive |
| 26.3 | Every `in`/`out` requires `layout(location = N)` | Compile failure |
| 26.3 | `gl_VertexID` becomes `gl_VertexIndex` | Compile failure |
| 26.3 | `DynamicTransforms` members reordered: `ModelViewMat, TextureMat, ColorModulator, ModelOffset` | Compiles cleanly; `std140` positional offset reads `TextureMat` as `ColorModulator` with zero log output |
| 26.3 | `post_effect/transparency` removed with Fabulous chain | Chained hooks stop running |

## Diagnostic Rules Reference

| Rule | Severity | Description |
|---|---|---|
| `SM001`..`SM003` | Error / Warning | Dead pre-1.21.5 shader JSON configs and unknown `minecraft:` core/include overrides |
| `SM010` | Info | Overriding a shared vanilla GLSL include (`last pack in stack wins`) |
| `SM020`..`SM021` | Error | Disallowed `#version` in GLSL includes (26.3+) or mismatched GLSL `#version` |
| `SM030`..`SM032` | Error | Wrong `#moj_import`/`#include` directive, missing `minecraft:` include, or missing custom-namespace include (`<ns:file.glsl>`) |
| `SM040`..`SM041` | Error / Warning | Positional `layout(std140) uniform` block member mismatch against vanilla |
| `SM050`..`SM051` | Error | Missing `layout(location = N)` or legacy `gl_VertexID` on 26.3+ |
| `SM060`..`SM065` | Error / Warning | Removed `transparency` chain, invalid post-effect JSON schema, stale vertex shader, or missing custom post-effect `.vsh`/`.fsh` source |
| `SM070`..`SM074` | Warning | Unparseable `pack.mcmeta`, missing format declaration, format range mismatch, or missing/empty `overlays.entries` directory |

## Getting Started

Requires Java 21+. Download `shadersmith-0.2.0.jar` from [Releases](https://github.com/SalyyS1/shadersmith/releases).

```sh
# Validate one version or a multi-version range (stacking active pack.mcmeta overlays per version)
java -jar shadersmith-0.2.0.jar check pack/ --mc 26.2
java -jar shadersmith-0.2.0.jar check pack.zip --mc-range 1.21.6..26.3-pre-1

# Emit SARIF v2.1.0 or JSON for CI
java -jar shadersmith-0.2.0.jar check pack.zip --mc 26.3-pre-1 --sarif shadersmith.sarif
java -jar shadersmith-0.2.0.jar check pack.zip --mc 26.3-pre-1 --json > findings.json

# Preview unified diff, then migrate directory or .zip to a byte-deterministic .zip with SHA-1
java -jar shadersmith-0.2.0.jar migrate pack.zip --to 26.3-pre-1
java -jar shadersmith-0.2.0.jar migrate pack.zip --to 26.3-pre-1 --zip pack-26.3.zip

# Scaffold a 26.3+ custom post-effect with matched JSON + std140 uniform block
java -jar shadersmith-0.2.0.jar new vignette --ns myfx --mc 26.3-pre-1 --uniform Strength:float=0.6

# Inspect the extracted vanilla contract for any supported version
java -jar shadersmith-0.2.0.jar contract --mc 26.3-pre-1
```

Exit code `1` means the pack has errors for the target version; `2` means invalid arguments or unreadable input.

## Overlay-Aware Multi-Version Checking (`pack.mcmeta` Overlays)

Multi-version HUD and shader packs (such as `BetterHud` packs) rely on `overlays.entries` in `pack.mcmeta` to
ship different shaders per format range (`formats: [63, 74]`, `{min_inclusive, max_inclusive}`, or
`min_format`/`max_format`).

When running `check --mc` or `check --mc-range`, `shadersmith` evaluates `overlays.entries` for each target
version's `pack_format`, stacks matching overlay `assets/...` files over the base pack in declaration order,
validates the effective shader tree (including cross-overlay include resolution), and reports diagnostics at
their exact physical file path (`overlay_26_3/assets/...`) so IDE links and GitHub SARIF annotations point to
the real file on disk. Rule `SM074` flags declared overlay directories that are missing or contain no files.

```
$ shadersmith check hud-pack/ --mc-range 1.21.6..26.3-pre-1
== hud-pack on 1.21.6: 0 error(s), 4 warning(s)
== hud-pack on 1.21.10: 0 error(s), 2 warning(s)
== hud-pack on 1.21.11: 1 error(s), 2 warning(s)
== hud-pack on 26.3-pre-1: 12 error(s), 2 warning(s)

Valid without errors on: 1.21.6..1.21.10
pack.mcmeta for the widest window: min_format 63, max_format 69
```

## Migrating Directories and `.zip` Packs

`migrate` applies every deterministic mechanical rewrite: `#moj_import` to `#include`, stripping `#version`
from includes on 26.3+, renaming `gl_VertexID` to `gl_VertexIndex`, reordering `std140` uniform blocks to
match the target version's exact layout, and removing dead pre-1.21.5 JSON program files.

When migrating a `.zip` archive with `--out <dir>` or `--zip <out.zip>`, `shadersmith` extracts all pack
entries safely (with zip-slip path traversal and entry size guards), preserves binary textures, sounds, fonts,
and models untouched, applies the shader rewrites, and (when `--zip` is given) writes a byte-reproducible
archive with normalized timestamps and prints `resource-pack-sha1=<hex>` ready for `server.properties`.

```
--- a/assets/minecraft/shaders/core/gui.vsh
+++ b/assets/minecraft/shaders/core/gui.vsh
@@ -2,13 +2,12 @@

 layout(std140) uniform DynamicTransforms {
     mat4 ModelViewMat;
+    mat4 TextureMat;
     vec4 ColorModulator;
     vec3 ModelOffset;
-    mat4 TextureMat;
-    float LineWidth;
 };
```

Changes that require human judgment (such as choosing `layout(location = N)` indices to match vertex formats)
are never guessed; they remain clearly listed with file and line numbers after migration.

## GitHub Action & Code Scanning Integration

Run `SalyyS1/shadersmith` in CI and upload findings directly to GitHub Security Code Scanning via SARIF v2.1.0:

```yaml
- name: Validate Resource Pack Shaders
  uses: SalyyS1/shadersmith@v0.2.0
  with:
    pack: ./resourcepack
    mc: '26.3-pre-1'
    sarif: shadersmith.sarif

- name: Upload SARIF to GitHub Code Scanning
  if: always()
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: shadersmith.sarif
```

## Scaffolding a Post Effect (`new`)

Minecraft 26.3 added `/posteffect add <player> <id>` plus the `minecraft:end_of_frame` hook, allowing servers
to trigger custom screen-space GLSL post effects with custom `std140` uniform blocks without client mods:

```
$ shadersmith new vignette --ns myfx --mc 26.3-pre-1 --uniform Strength:float=0.6 --uniform Tint:vec3=0.1,0,0
wrote  assets/myfx/post_effect/vignette.json
wrote  assets/myfx/shaders/post/vignette.fsh
wrote  posteffect-commands.txt

Trigger it in game with: /posteffect add @s myfx:vignette
```

The generated JSON pass graph and GLSL uniform block share identical member definitions and pass `shadersmith check` out of the box.

## Scope & Non-Goals

- **Per-pipeline `#define` flags:** Since 1.21.5, render-pipeline `#define` flags live in client Java code rather than data assets.
- **Full GLSL compiler:** `shadersmith` validates structural vanilla contracts (directives, version headers, `std140` block member order, post-effect wiring, overlays), not arbitrary GLSL syntax errors.
- **Sodium / Iris / OptiFine custom pipelines:** Contracts reflect vanilla Minecraft client rendering.

## Related Salyvn Minecraft Toolchain

- [packmigrate](https://github.com/SalyyS1/packmigrate): Migrate legacy 1.14-1.21.3 `overrides` resource packs to modern `items/*.json` item definitions (1.21.4 through 26.2).
- [packmerge](https://github.com/SalyyS1/packmerge): Deterministic multi-plugin resource pack compiler with conflict auditing and `resource-pack-sha1` output.
- [glyphsmith](https://github.com/SalyyS1/glyphsmith): Deterministic custom bitmap font compiler, PUA allocator, and glyph auditor.
- [foliascan](https://github.com/SalyyS1/foliascan): JVM bytecode static analyzer for Paper-to-Folia regionized multithreading migration with SARIF v2.1.0 output.
- [minecraftkit](https://github.com/SalyyS1/minecraftkit): MCP server giving AI coding assistants Paper/Folia API auditing, crash log diagnostics, and version-aware tooling.

## Building from Source

```sh
./gradlew build --no-daemon
```

Kotlin 2.4.10 on Java 21, Gradle 9.7.1, producing a reproducible shaded CLI JAR in `build/libs/`.
`tools/build-contract-dataset.py` regenerates the contract dataset from [misode/mcmeta](https://github.com/misode/mcmeta) per-version vanilla metadata (shipping only structural facts, zero Mojang assets).

## Credits & License

See `NOTICE` for upstream references ([misode/mcmeta](https://github.com/misode/mcmeta), [toxicity188/BetterHud](https://github.com/toxicity188/BetterHud), [JNNGL/vanilla-shaders](https://github.com/JNNGL/vanilla-shaders), [ComunidadAylas/PackSquash](https://github.com/ComunidadAylas/PackSquash), [seailz/shader-devtools](https://github.com/seailz/shader-devtools)). Independent project; not affiliated with Mojang or Microsoft.

Apache-2.0. See `LICENSE`.
