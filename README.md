# shadersmith

[![build](https://github.com/SalyyS1/shadersmith/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/shadersmith/actions/workflows/build.yml)

The Minecraft client does not validate a resource pack's shaders against anything. It compiles them, and if
they compile, it runs them. A shader written for the previous version compiles fine on the next one and
reads the wrong uniform, because the contract it was written against — which include directive, which GLSL
version, which members a uniform block has and in what order — changed without a compile error to say so.

shadersmith holds that contract for each version, extracted from the game's own files, and checks a pack
against it:

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

Then it rewrites what is mechanical, shows you the diff first, and tells you what still needs a person.

## The changes it knows about

Every one of these was found by reading the vanilla shader files on both sides of the change, and the
dataset the tool ships was generated from those files rather than typed in. Where a project's source
comment disagreed with the files, the files won.

| Version | What changed | What happens if you miss it |
|---|---|---|
| 1.21.5 | `shaders/core/*.json` program definitions deleted; only the GLSL is read | The JSON is silently ignored. Whatever it re-pointed a render type at stops happening. Packs still ship these files today. |
| 1.21.5 | `shaders/post/*.json` program configs deleted; a post effect names its shaders directly | Same |
| 1.21.6 | Post effect `uniforms` becomes a map from block name to members | The list form fails to parse |
| 1.21.9 | GLSL `#version 150` becomes `#version 330`; post passes use `minecraft:core/screenquad` | Compile error, or a pass that references a vertex shader that moved |
| 1.21.11 | `LineWidth` leaves the `DynamicTransforms` block and becomes a vertex attribute | A block that still declares it compiles and reads past the buffer. One popular plugin's source says this happened at 1.21.9; it did not. |
| 26.3 | `#moj_import` becomes `#include` | Preprocess failure |
| 26.3 | Include files no longer carry `#version` | Compile failure on the second directive |
| 26.3 | Every `in`/`out` needs `layout(location = N)` | Compile failure |
| 26.3 | `gl_VertexID` becomes `gl_VertexIndex` | Compile failure |
| 26.3 | `DynamicTransforms` members are **reordered**: `ModelViewMat, TextureMat, ColorModulator, ModelOffset` | Compiles clean. std140 is positional, so the shader reads `TextureMat` where it wants `ColorModulator`. Nothing in any log. |
| 26.3 | `post_effect/transparency` removed with the Fabulous chain | Anything hooked into it stops running |

That last one is the reason this tool exists. Same members, different order, no error, wrong picture.

## Getting started

Java 21 or newer. Download `shadersmith.jar` from the releases page.

```sh
shadersmith check pack/ --mc 26.2                       # one version
shadersmith check pack/ --mc-range 1.21.6..26.3-pre-1   # every known version in the range
shadersmith check pack.zip --mc 26.3-pre-1 --sarif out.sarif   # for CI
shadersmith migrate pack/ --to 26.3-pre-1               # show the diff
shadersmith migrate pack/ --to 26.3-pre-1 --write       # apply it
shadersmith new vignette --ns myfx --mc 26.3-pre-1 --uniform Strength:float=0.6
shadersmith contract --mc 26.3-pre-1                    # what the tool thinks the version wants
```

Exit code 1 means the pack has errors for the version; 2 means the command could not run.

## Checking a range

`--mc-range` runs every version between the two ends and reports the window the pack is actually valid
in, which is the honest answer to what `pack.mcmeta` should claim:

```
$ shadersmith check hud-pack/ --mc-range 1.21.6..26.3-pre-1
== hud-pack on 1.21.6: 0 error(s), 4 warning(s)
== hud-pack on 1.21.8: 0 error(s), 4 warning(s)
== hud-pack on 1.21.9: 0 error(s), 2 warning(s)
== hud-pack on 1.21.10: 0 error(s), 2 warning(s)
== hud-pack on 1.21.11: 1 error(s), 2 warning(s)
== hud-pack on 26.1: 1 error(s), 2 warning(s)
== hud-pack on 26.2: 1 error(s), 1 warning(s)
== hud-pack on 26.3-pre-1: 12 error(s), 2 warning(s)

Valid without errors on: 1.21.6..1.21.10
pack.mcmeta for the widest window: min_format 63, max_format 69
```

That pack claimed `63..88`. It works to 69, and the thing that breaks it at 75 is one line in one block. This
is the case that pack overlays exist for, and the range check tells you where to cut them.

## Migrating

`migrate` applies every fix that is the same edit a careful person would make by hand: the directive
spelling, the `#version` in an include, the `gl_VertexID` rename, a uniform block reordered to the target
version's layout, a file the client no longer reads. It prints a unified diff and does nothing until you
pass `--write`; a zip has to go to `--out` because it will not be rewritten in place.

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

What it does not touch is anything that needs a decision. `layout(location = N)` is the main one: the
numbers are assigned in declaration order per direction and have to agree with the vertex format on the
other side, and a wrong guess there is a shader that compiles and draws from the wrong attribute. Those
stay as findings under "still needs a person", with the file and line.

## Scaffolding a post effect

26.3 added `/posteffect add <player> <id>`, which lets a server switch a screen-space effect on for one
player with no client mod — and an always-on `minecraft:end_of_frame` hook that runs whenever the pack is
loaded. Post effects are the one place a resource pack may introduce its own GLSL and its own uniform
block, so this is the part of the shader pipeline that opened up rather than closed.

`new` writes an effect that loads on the target version:

```
$ shadersmith new vignette --ns myfx --mc 26.3-pre-1 --uniform Strength:float=0.6 --uniform Tint:vec3=0.1,0,0
wrote  assets/myfx/post_effect/vignette.json
wrote  assets/myfx/shaders/post/vignette.fsh
wrote  posteffect-commands.txt

Trigger it in game with: /posteffect add @s myfx:vignette
```

The definition follows the shape of vanilla's own `invert.json` for that version — one pass from
`minecraft:main` into a swap target and a blit back — because that is the shape known to load. The uniforms
you name on the command line are declared identically in the JSON and in the GLSL block, so the two cannot
drift apart, and the block is named after your effect so two packs never claim the same one. `--always-on`
also writes the `end_of_frame` hook, and warns you that the last pack in the list wins if another pack
defines it too.

What comes out passes its own `check` on the target version; that is a test.

## The contract

`contract --mc <version>` prints what the tool believes, which is also how you check the tool against the game:

```
$ shadersmith contract --mc 26.3-pre-1
26.3-pre-1  (resource pack format 97.1)
   glsl            #version 330
   include         #include <minecraft:name.glsl>   (includes carry no #version)
   core programs   63 files, GLSL only
   post effects    blur, creeper, entity_outline, invert, spider   (schema: uniform-blocks, vertex: minecraft:core/screenquad)
   uniform blocks  10
      DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset }
      Fog { vec4 FogColor; float FogEnvironmentalStart; ... }
      ...
```

The dataset covers 1.21.4 through 26.3-pre-1, one row per version: legal file names under `core`,
`include` and `post`; every `layout(std140) uniform` block with its members in order; the GLSL version; the
include directive; whether includes carry `#version`; the post effect schema era; and the vertex shader post
passes use. `tools/build-contract-dataset.py` regenerates it from the vanilla assets that
[misode/mcmeta](https://github.com/misode/mcmeta) publishes per version, so adding a release is one
command. No Mojang asset is shipped — only structural facts about them.

## What it does not do

- **Per-pipeline `#define` values.** Since 1.21.5 the defines the engine sets for each render pipeline live
  in client Java, not in data, so they cannot be derived from the assets. The tool checks what a pack's
  shader declares, not what the engine will define around it.
- **GLSL semantics.** This is not a parser. The contract violations that actually bite are structural — a
  directive, a version line, a member out of order — and all of them sit on a line a regular expression
  finds. A shader that is wrong in a way only a compiler notices is still wrong.
- **Merging two packs.** When two packs override the same vanilla include, the last one in the list wins
  and there is no message. The tool tells you a pack does that (rule SM010) and stops there; deciding what
  the merged file should say is not something a tool should do quietly.
- **Sodium, Iris, OptiFine.** Sodium replaces the terrain shaders with its own and leaves the rest alone;
  what Iris and OptiFine do to pack shaders is not something I have verified and the tool makes no claim.
- **Effect generation for core shaders.** Since 1.21.5 there is no per-render-type targeting, so every
  "effect" is an edit to one of a dozen shared files. That is a template, not a tool.
- **Snapshot tracking.** The dataset has releases plus the current pre-release. A snapshot in between
  resolves to the release before it, which will be wrong when the snapshot changed something. Pass the
  release you mean.

## Building it

```sh
./gradlew build          # tests, then the shaded jar in build/libs
```

Kotlin 2.4.10 on a Java 21 toolchain, Gradle 9.7.1, one shaded jar with Clikt and kotlinx.serialization
inside. The test suite pins the facts each rule depends on — `LineWidth` leaving at 1.21.11 and not 1.21.9,
the 26.3 member reorder, the directive change — so that regenerating the dataset after a release cannot
silently change what a rule means. A fixture pack written in the shape plugins actually ship is checked on
four versions and migrated to 26.3, and the result is checked again.

## Credits

`NOTICE` lists what the tool depends on, where each fact came from, and the projects that were read to
understand the problem. A few worth naming:

[misode/mcmeta](https://github.com/misode/mcmeta) publishes every version's assets under a tag, which is
what makes a per-version contract possible to extract instead of remember.
[toxicity188/BetterHud](https://github.com/toxicity188/BetterHud) ships shaders through pack overlays keyed
to format ranges, which is the right shape and the one the range check is designed to feed.
[JNNGL/vanilla-shaders](https://github.com/JNNGL/vanilla-shaders) is the reference collection of what core
shaders could do up to 1.21.4. [ComunidadAylas/PackSquash](https://github.com/ComunidadAylas/PackSquash)
parses GLSL properly and is the right thing to run after this.
[seailz/shader-devtools](https://github.com/seailz/shader-devtools) inspects pipelines from inside a running
client, which is the only place the per-pipeline defines can be seen.

shadersmith is an independent project and is not affiliated with, endorsed by or sponsored by Mojang or
Microsoft.

## Licence

Apache-2.0. See `LICENSE`.
