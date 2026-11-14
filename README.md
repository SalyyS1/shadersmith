# shadersmith

[![build](https://github.com/SalyyS1/shadersmith/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/shadersmith/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/SalyyS1/shadersmith)](https://github.com/SalyyS1/shadersmith/releases)
[![Browser Auditor](https://img.shields.io/badge/web_inspector-salyys1.github.io%2Fshadersmith-3b63ac)](https://salyys1.github.io/shadersmith/)

The Minecraft client does not validate a resource pack's shaders against anything. It compiles them, and if
they compile, it runs them. A shader written for a previous version can compile cleanly on the next release and
read the wrong uniform, because the contract it was written against (which include directive, which GLSL
version, which members a `std140` uniform block has and in what order) changed without raising a compile error.

`shadersmith` holds that per-version contract, extracted directly from vanilla shader files across `1.21.4`
through `26.3` (`pack_format` `46` through `97.1`), and checks or migrates a resource pack directory or `.zip`
archive against it. Try it zero-install in your browser at
**[https://salyys1.github.io/shadersmith/](https://salyys1.github.io/shadersmith/)** or run the CLI and
GitHub Action in CI:

```
$ shadersmith check hud-pack/ --mc 26.3
== hud-pack on 26.3: 12 error(s), 2 warning(s)
   error   SM041  assets/minecraft/shaders/core/gui.vsh:3
           Uniform block DynamicTransforms does not match vanilla 26.3. Declared: mat4 ModelViewMat,
           vec4 ColorModulator, vec3 ModelOffset, mat4 TextureMat, float LineWidth. Vanilla: mat4 ModelViewMat,
           mat4 TextureMat, vec4 ColorModulator, vec3 ModelOffset. A member that does not exist reads garbage;
           a missing one shifts every member after it.
           fix: rewrite file to match target version contract
   error   SM030  assets/minecraft/shaders/core/text.fsh:3
           #moj_import is not the include directive on 26.3; it is #include. The file will not preprocess.
           fix: line 3 -> '#include <minecraft:fog.glsl>'
   error   SM020  assets/minecraft/shaders/include/fog.glsl:1
           An include must not declare #version on 26.3; the directive belongs on the first line of the
           main shader only, and a second one fails compilation.
           fix: delete line 1
   ...
```

Then it rewrites what is mechanical, prints a unified diff first, preserves all non-shader pack assets (for
both directory and `.zip` inputs), and lists what still needs a human decision.

## Interactive Browser GLSL & Overlay Inspector

Open **[https://salyys1.github.io/shadersmith/](https://salyys1.github.io/shadersmith/)** to drop a `.zip`
resource pack or edit GLSL and `post_effect/*.json` files locally in your browser (via `DecompressionStream`,
with zero file uploads):

- **Full `1.21.4..26.3` Release Compatibility Matrix:** Stacks active `pack.mcmeta` `overlays.entries` per
  version and computes the widest error-free `min_format` / `max_format` range.
- **`std140` Byte-Offset Diff Explorer:** Compares byte offsets (`[0..64)`, `[64..128)`, `[128..144)`) across
  Minecraft versions for `DynamicTransforms`, `Globals`, `Fog`, `LightmapInfo`, and `CloudInfo`.
- **One-Click Mechanical 26.3 Migrator & OASIS SARIF v2.1.0 Export.**

## Vanilla Shader Contract Changes Covered

Every contract entry was generated directly from vanilla shader metadata on both sides of each version boundary:

| Version | What changed | What happens if you miss it |
|---|---|---|
| 1.21.5 | `shaders/core/*.json` program definitions deleted; only GLSL is read | JSON is silently ignored; render-type remapping stops running |
| 1.21.5 | `shaders/post/*.json` program configs deleted; post effects name shaders directly | Silently ignored |
| 1.21.6 | Post effect `uniforms` becomes a map from block name to members | List form fails to parse |
| 1.21.9 | GLSL `#version 150` becomes `#version 330`; post passes use `minecraft:core/screenquad` | Compile error or missing vertex shader reference |
| 1.21.11 | `LineWidth` leaves `DynamicTransforms` and becomes a vertex attribute | Block compiles cleanly and reads past buffer |
| 26.3 (`97.1`) | `#moj_import` becomes `#include` | Preprocess failure |
| 26.3 (`97.1`) | Include files no longer carry `#version` | Compile failure on second `#version` directive |
| 26.3 (`97.1`) | Every file-scope `in`/`out` requires `layout(location = N)` | Compile failure |
| 26.3 (`97.1`) | `gl_VertexID` becomes `gl_VertexIndex` | Compile failure |
| 26.3 (`97.1`) | `DynamicTransforms` members reordered: `ModelViewMat, TextureMat, ColorModulator, ModelOffset` | Compiles cleanly; `std140` positional offset reads `TextureMat` as `ColorModulator` with zero log output |
| 26.3 (`97.1`) | `post_effect/transparency` removed with Fabulous chain | Chained hooks stop running |

## Diagnostic Rules Reference

| Rule | Severity | Description |
|---|---|---|
| `SM001`..`SM002` | Warning | Obsolete pre-1.21.5 `shaders/core/*.json` and `shaders/post/*.json` program configs |
| `SM010`..`SM011` | Note / Warning | Overriding a shared vanilla GLSL include or targeting a non-existent `minecraft:` shader |
| `SM020`..`SM022` | Error / Warning | Disallowed `#version` in GLSL includes (26.3+), missing `#version`, or mismatched GLSL dialect |
| `SM030`..`SM033` | Error | Wrong `#moj_import`/`#include` directive, missing `minecraft:` or custom `<ns:file.glsl>` include, or circular GLSL include dependency chain (`SM033`) |
| `SM040`..`SM041` | Error | Positional `layout(std140) uniform` block member order or member schema mismatch against vanilla |
| `SM050`..`SM051` | Error | Missing `layout(location = N)` qualifier (including `flat`/`smooth` qualifiers) or legacy `gl_VertexID` on 26.3+ |
| `SM060`..`SM064` | Error / Warning | Legacy post-effect pass schema, removed `transparency` chain, stale vanilla vertex shader, or missing custom/vanilla `.vsh`/`.fsh` source |
| `SM065`..`SM068` | Error / Warning | Undeclared or read-before-write internal post-effect render target (`SM065`), unbound shader `uniform sampler2D` vs pass `inputs` (`SM066`), pass `uniforms` vs shader `std140` layout mismatch (`SM067`), or malformed `post_effect/*.json` (`SM068`) |
| `SM070`..`SM075` | Error / Warning | Unreadable `pack.mcmeta`, missing format fields, format range mismatch, missing `min_format`/`max_format` above 64, empty overlay directory, or inverted format bounds / invalid overlay directory name (`SM075`) |

## Getting Started

Requires Java 21+. Download `shadersmith-0.3.0.jar` from [Releases](https://github.com/SalyyS1/shadersmith/releases).

```sh
# Validate one version or a multi-version release range (stacking active pack.mcmeta overlays per version)
java -jar shadersmith-0.3.0.jar check pack/ --mc 26.3
java -jar shadersmith-0.3.0.jar check pack.zip --mc-range 1.21.6..26.3

# Emit OASIS SARIF v2.1.0 or JSON for CI
java -jar shadersmith-0.3.0.jar check pack.zip --mc 26.3 --sarif shadersmith.sarif
java -jar shadersmith-0.3.0.jar check pack.zip --mc 26.3 --format sarif > shadersmith.sarif
java -jar shadersmith-0.3.0.jar check pack.zip --mc 26.3 --json > findings.json

# Preview unified diff, then migrate directory or .zip to a byte-deterministic .zip with SHA-1
java -jar shadersmith-0.3.0.jar migrate pack.zip --to 26.3
java -jar shadersmith-0.3.0.jar migrate pack.zip --to 26.3 --zip pack-26.3.zip

# Scaffold a 26.3+ custom post-effect with matched JSON + std140 uniform block
java -jar shadersmith-0.3.0.jar new vignette --ns myfx --mc 26.3 --uniform Strength:float=0.6

# Inspect the extracted vanilla contract for any supported version or format tuple (e.g. 97.1)
java -jar shadersmith-0.3.0.jar contract --mc 26.3
```

Exit code `1` means the pack has errors for the target version; `2` means invalid arguments or unreadable input.

## Overlay-Aware Multi-Version Checking (`pack.mcmeta` Overlays)

Multi-version HUD and shader packs (such as `BetterHud` packs) rely on `overlays.entries` in `pack.mcmeta` to
ship different shaders per format range (`formats: [63, 74]`, `{min_inclusive, max_inclusive}`, or
`min_format`/`max_format` integers and `[major, minor]` tuples).

When running `check --mc` or `check --mc-range`, `shadersmith` evaluates `overlays.entries` for each target
version's `pack_format`, stacks matching overlay `assets/...` files over the base pack in declaration order,
validates the effective shader tree (including cross-overlay include resolution, cycle detection, and post-effect
uniform/sampler bindings), and reports diagnostics at their exact physical file path (`overlay_26_3/assets/...`)
so IDE links and GitHub SARIF annotations point to the real file on disk. Rules `SM074` and `SM075` flag missing
overlay directories, invalid overlay directory characters, and inverted format bounds.

## Migrating Directories and `.zip` Packs

`migrate` applies every deterministic mechanical rewrite: `#moj_import` to `#include`, stripping `#version`
from includes on 26.3+, renaming `gl_VertexID` to `gl_VertexIndex`, reordering `std140` uniform blocks to
match the target version's exact layout, updating stale `post_effect` `screenquad` vertex shader paths, and
removing dead pre-1.21.5 JSON program files.

When migrating a `.zip` archive with `--out <dir>` or `--zip <out.zip>`, `shadersmith` extracts all pack
entries safely (with zip-slip path traversal and entry size guards), preserves binary textures, sounds, fonts,
and models untouched, applies the shader rewrites, and (when `--zip` is given) writes a byte-reproducible
archive with normalized timestamps and prints `resource-pack-sha1=<hex>` ready for `server.properties`.

## GitHub Action & Code Scanning Integration

Run `SalyyS1/shadersmith` in CI and upload findings directly to GitHub Security Code Scanning via OASIS SARIF v2.1.0:

```yaml
- name: Validate Resource Pack Shaders
  id: shaders
  uses: SalyyS1/shadersmith@v0.3.0
  with:
    pack: ./resourcepack
    mc: '26.3'
    sarif: shadersmith.sarif
    fail-on-error: 'false'

- name: Upload SARIF to GitHub Code Scanning
  if: always()
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: ${{ steps.shaders.outputs.sarif-file }}
```

## Related Salyvn Minecraft Toolchain

- [glyphsmith](https://github.com/SalyyS1/glyphsmith): Deterministic custom bitmap font compiler, PUA allocator, multi-pack collision solver, and [browser font auditor](https://salyys1.github.io/glyphsmith/).
- [packmigrate](https://github.com/SalyyS1/packmigrate): Migrate legacy 1.14-1.21.3 `overrides` resource packs to modern `items/*.json` item definitions (1.21.4 through 26.3).
- [packmerge](https://github.com/SalyyS1/packmerge): Deterministic multi-plugin resource pack compiler with conflict auditing and `resource-pack-sha1` output.
- [foliascan](https://github.com/SalyyS1/foliascan): JVM bytecode static analyzer for Paper-to-Folia regionized multithreading migration with SARIF v2.1.0 output and [browser auditor](https://salyys1.github.io/foliascan/).
- [minecraftkit](https://github.com/SalyyS1/minecraftkit): MCP server giving AI coding assistants Paper/Folia API auditing, crash log diagnostics, and version-aware tooling.

## Building from Source

```sh
./gradlew build --no-daemon
```

Kotlin 2.4.10 on Java 21, Gradle 9.7.1, producing a reproducible shaded CLI JAR in `build/libs/`.
`tools/build-contract-dataset.py` regenerates or appends to the contract dataset from [misode/mcmeta](https://github.com/misode/mcmeta) per-version vanilla metadata (shipping only structural facts, zero Mojang assets).

## Credits & License

See `NOTICE` for upstream references ([misode/mcmeta](https://github.com/misode/mcmeta), [toxicity188/BetterHud](https://github.com/toxicity188/BetterHud), [JNNGL/vanilla-shaders](https://github.com/JNNGL/vanilla-shaders), [ComunidadAylas/PackSquash](https://github.com/ComunidadAylas/PackSquash), [seailz/shader-devtools](https://github.com/seailz/shader-devtools)). Independent project; not affiliated with Mojang or Microsoft.

Apache-2.0. See `LICENSE`.
