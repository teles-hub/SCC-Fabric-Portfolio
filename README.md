# SCC Fabric — Unofficial Port

**An unofficial, independent Fabric port of SCC – Super Cool Calculator for Minecraft 1.21.1.**

> **Resumo em português:** port não oficial do mod *SCC – Super Cool Calculator* (criado por XxRonaldxX, licença MIT) para Minecraft 1.21.1/Fabric, escrito em Java 21. Inclui redes de cabos, armazenamento, terminais, análise de receitas e autocrafting. O build limpo foi executado com sucesso e a release `v1.6.5-fabric-cv1.8.0` contém o JAR remapeado e o JAR de fontes, mas **não houve validação em jogo**. O texto abaixo permanece em inglês por ser um projeto de código aberto com créditos e licença do autor original.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-blue)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-In%20development-yellow)](#current-limitations)

## 1. About the Project

SCC Fabric adapts the non-combat systems of **SCC – Super Cool Calculator** to the Fabric modding ecosystem. The original mod was created for NeoForge by **XxRonaldxX** and is distributed under the MIT License.

This repository contains a source publication of that adaptation: recipe analysis, logistics and cable networks, storage blocks, terminals, autocrafting infrastructure and an optional Cobblemon Campfire Pot processor.

This is an **independent, unofficial port**. It is neither an official release nor a continuation authorized by the original author.

## 2. Original Project and Credits

| Role | Credit |
|---|---|
| Original project | **SCC – Super Cool Calculator** |
| Original creator | **XxRonaldxX** |
| Original platform | NeoForge, Minecraft 1.21.1 |
| Original license | MIT |
| Fabric port and adaptation | **Cauê Teles** |

Confirmed links for the original work:

- Source and issue tracker: https://github.com/xXxRonaldxXx/SuperCoolCalculator
- CurseForge page listed by the original project: https://www.curseforge.com/minecraft/mc-mods/scc-super-cool-calculator

All original authorship remains with XxRonaldxX. No partnership, personal authorization or official support is claimed.

## 3. Goal and Scope

The goal is to preserve the original mod's core ideas while rebuilding the platform-specific layers for Fabric. The port focuses on the non-combat subset and is **not** a complete one-to-one restoration of every upstream feature.

Target environment:

- Minecraft **1.21.1**
- Java **21** bytecode target
- Fabric Loader **0.19.5+** and Fabric API **0.116.18+**
- Cobblemon **1.8.4** / Cobbleverse **1.8.0** used as the compatibility profile
- Optional integrations suggested in metadata: JEI, EMI, Jade and Accessories

## 4. Storage Systems

The source includes item storage blocks, tiered storage upgrades and storage connectors, with registration data and recipes for the available tiers. The data set was checked statically for valid registrations and recipe references.

Runtime item, fluid and energy storage transactions have not been exercised in a playable world.

## 5. Network and Cable Infrastructure

The port includes cable blocks, a cable bus, connectors, network blocks and wireless endpoints. The published data uses the registered `scc:network_cable` identifier, and static audits reported no broken SCC cable references.

Live network traversal, transfer behavior and power distribution are not gameplay-validated.

## 6. Recipe System

The active data set contains recipes for shaped, shapeless, storage-upgrade and facade entries. Recipe registration, ingredient handling and compatibility helpers are included in the source.

JEI and EMI are optional integration points; their runtime behavior was not verified in a client session.

## 7. Autocrafting

The source includes scheduling, journaling, material reservation, save barriers and recovery-related infrastructure. Invariant checks were applied to the ordering and durability rules during development.

No complete end-to-end autocrafting job was executed in a real Minecraft world.

## 8. Cobblemon Integration

An SCC machine processor for the Cobblemon Campfire Pot is implemented through the project's processor contract, using `SavedData` for operation state and reflection for compatibility with the Cobblemon API. The Cobblemon 1.8.4 API was checked against the binary artifact during development.

No in-game cooking or Campfire Pot interaction was performed. Known save-path limitations remain: there is no fsync or atomic transaction guarantee, and journals above the configured maximum can be lost when loading.

## 9. Interfaces and Terminals

Terminal, menu and screen classes and their recipe coverage are present in the source. GUI opening, synchronization and player interaction were not tested with a connected client.

## 10. Technology Stack

| Technology | Use |
|---|---|
| Java 21 | Language and bytecode target |
| Fabric Loader / Fabric API | Mod runtime |
| Fabric Loom / Gradle | Build and dependency tooling |
| Minecraft 1.21.1 mappings | Development mappings |
| Cobblemon 1.8.4 | Optional Campfire Pot integration |
| JEI / EMI / Jade / Accessories | Optional compatibility points |
| Python and PowerShell | Static validation harnesses used during development |

## 11. Development and Validation

Development used isolated worktrees, automated static harnesses and headless server startup checks. Recorded results include:

- A successful Gradle build from the clean public checkout: `./gradlew build`, with Java 21 bytecode targeting.
- 71 automated harnesses and 6 static audits, all reporting zero failures in the private development workspace.
- Headless Fabric startup reaching `Done`, with no broken SCC tags or invalid cable references.
- Partial modded headless startup with a reduced mod set; the remaining messages were attributed to upstream Cobblemon data fixers.

These checks were performed in the private development workspace. The harness scripts and audit tooling are not included in this public source tree, so visitors cannot reproduce them from this checkout. A GitHub Actions workflow at `.github/workflows/build.yml` now runs the public `build` on pushes and pull requests to `main` and uploads the remapped JAR as an artifact; the private harnesses are still not reproduced there.

The public build was executed with JDK 27 available on the build machine while the Gradle configuration retained Java 21 bytecode targeting; a JDK 21 toolchain was not installed locally.

These are build-time and headless checks only. They do **not** constitute gameplay validation.

## 12. Tool-Assisted Development

AI-assisted coding tools, including OpenCode and coding assistants, were used to support code analysis, implementation, documentation and review. Suggestions were reviewed and checked against the project's requirements and automated checks before incorporation.

The work remains a human-directed port and adaptation; AI tools are not credited as the authors of the original project.

## 13. Building from Source

Clone the repository and run the Gradle wrapper:

```powershell
.\gradlew.bat build
```

```bash
./gradlew build
```

The build script targets Java 21 bytecode and, on `check`, resolves the Cobblemon API artifact from Modrinth to verify binary API compatibility. No third-party JARs or bytecode dumps are committed to this repository.

### Source layout

| Package | Responsibility visible in the source |
|---|---|
| `de.scc.calculator.api` | Public seams used by optional integrations and terminals |
| `de.scc.calculator.calc` | Recipe book, recipe sources, calculation engine, crafting plan and the independent `StackKey`, `ItemGraph`, `AnalysisJob` and `Consumption` helpers |
| `de.scc.calculator.client` | Client lifecycle, screens and JEI transfer handler |
| `de.scc.calculator.compat` | Optional integrations, including the Cobblemon Campfire Pot processor |
| `de.scc.calculator.network` | Cables, storage, menus, job queue, payloads and reservation/escrow logic |
| `de.scc.calculator.platform` | Fabric-side platform adapters and registries |
| `de.scc.calculator.recipe`, `.registry`, `.data`, `.hooks`, `.mixin` | Recipe adapters, registration, balance data, event hooks and the single mixin |

Resources under `src/main/resources` contain mod metadata, registrations, recipes, lang files, textures and bundled fonts with their OFL notices.

### Reproducible verification

The commands below were executed from a fresh clone of the public repository. `build` compiles all sources and runs `check`; `check` depends on `verifyCobblemonBinaryApi`, which downloads the Cobblemon 1.8.4 JAR from Modrinth into the Gradle cache and uses the local `javap` binary to assert 14 method/field signatures. The downloaded JAR is an inspection-only dependency and is never packaged into the mod JAR.

```powershell
.\gradlew.bat build --console=plain --no-daemon
```

```bash
./gradlew build --console=plain --no-daemon
```

Observed result in this checkout: `BUILD SUCCESSFUL in 1m 57s`, 8 actionable tasks executed, including `compileJava`, `jar`, `remapJar`, `check` and the 14 Cobblemon binary API assertions. The build machine had JDK 27 installed; Gradle still compiled with `options.release = 21`. A GitHub Actions workflow now performs the same build on `main` with JDK 27 (the runner's default JDK; bytecode target stays Java 21 via `options.release = 21`); game-world validation is still absent.

## 14. Current Limitations

- No real client, player-connected world or GUI session was used for validation.
- Crafting, storage transactions, cable networking, wireless behavior and Cobblemon cooking remain untested in gameplay.
- The optional JEI, EMI, Jade and Accessories integrations, and the full Cobbleverse modpack profile, were not runtime-verified.
- The upstream Cobblemon data-fixer messages observed in a partial modded startup are external to SCC.
- Independent Java implementations of the stack-key, item-graph, analysis-job and consumption helpers are included. They were written from the contracts used by the public source tree, without reusing original bytecode or internal bytecode dumps, and they allow a clean checkout to compile.
- The project is under active development and is not a production-ready release.

## 15. License and Attribution

The original SCC project is licensed under the MIT License. The original copyright notice, `Copyright (c) 2026 XxRonaldxX`, is preserved in the [LICENSE](LICENSE) file and in the mod metadata.

Bundled fonts are distributed under the SIL Open Font License 1.1; their notices are included in `src/main/resources/assets/scc/font/licenses/`.

Port and adaptation: **Cauê Teles**.

## 16. Disclaimer

SCC Fabric is an unofficial, independently maintained adaptation. It is not affiliated with, endorsed by or officially supported by XxRonaldxX. All original concepts and applicable intellectual property remain credited to their respective creators.
