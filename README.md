# SCC-Fabric-Portfolio
Port não oficial do SCC – Super Cool Calculator de NeoForge para Fabric 1.21.1. Projeto independente focado em armazenamento, redes, análise de receitas e autocrafting. Original criado por XxRonaldxX, sob licença MIT.
# SCC Fabric — Unofficial Port

**An unofficial Fabric port of SCC – Super Cool Calculator for Minecraft 1.21.1.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-blue)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-In%20Development-yellow)](#project-status)

## About the Project

SCC Fabric is an independent, unofficial port of **SCC – Super Cool Calculator**, originally created by **XxRonaldxX** for NeoForge.

The purpose of this project is to adapt the original mod's systems and architecture to the Fabric modding environment while preserving its core concepts and functionality.

The port involves analyzing existing code, adapting platform-specific APIs, rebuilding integrations and implementing compatibility with the Fabric ecosystem.

This project is primarily focused on Minecraft 1.21.1, with compatibility work targeting Cobblemon 1.8.4 and Cobbleverse 1.8.0.

## Original Project & Credits

All original credit belongs to **XxRonaldxX**, the creator of SCC – Super Cool Calculator.

- **Original Mod:** SCC – Super Cool Calculator
- **Original Author:** XxRonaldxX
- **Original Mod Loader:** NeoForge
- **Original Platform:** Minecraft
- **Original License:** MIT

**Original project:**
https://www.curseforge.com/minecraft/mc-mods/scc-super-cool-calculator

**Original source and issue tracker:**
https://github.com/xXxRonaldxXx/SuperCoolCalculator

This repository contains an independent, unofficial port and adaptation. It is not an official release, continuation or partnership with the original author.

The original author's copyright notice and MIT license terms must be preserved.

## Project Scope

The port focuses on the non-combat systems of SCC.

### Storage Systems

- Item storage and inventory management.
- Fluid and chemical storage foundations.
- Storage tiers and upgrade progression.
- Storage connectors and network access.
- Data persistence and synchronization.

### Network Infrastructure

- Physical cable connections.
- Cable Bus infrastructure.
- Network discovery and topology.
- Resource access and transfer foundations.
- Wireless infrastructure under development.

### Recipe System

- Recipe analysis and material calculations.
- Recipe registration and compatibility.
- Ingredient and resource management.
- JEI recipe integration.
- Crafting progression.

### Autocrafting

- Craft job management.
- Material reservation.
- Operation journaling and recovery foundations.
- Machine processor interfaces.
- Cobblemon cooking integration.

### User Interface

- Crafting terminals.
- Network interfaces.
- Storage management screens.
- Player-facing configuration and information.

## Technology Stack

| Technology | Purpose |
|---|---|
| Java 21 | Main programming language and target bytecode |
| Fabric | Minecraft modding platform |
| Gradle | Build automation |
| Git | Version control |
| GitHub Actions | Continuous integration |
| Python | Automated validation and auditing |
| Cobblemon API | Optional integration |

## Development & Validation

The project uses automated build processes, regression tests and structural audits to verify the port.

Documented validation includes:

- Successful Gradle build.
- Automated regression test suite.
- Resource and registry audits.
- Recipe integrity checks.
- Cobblemon binary API verification.
- Headless Fabric server initialization.
- Modded server initialization with selected dependencies.

### Current Limitations

Passing automated tests and server initialization does not mean every feature is fully functional in a playable Minecraft world.

Client-side interaction, complete gameplay validation, machine processing, network behavior and several integrations still require further testing.

The project must therefore be considered **under active development**, not a complete or production-ready release.

## Artificial Intelligence-Assisted Development

AI-assisted programming tools are used as part of the development workflow, including OpenCode and coding assistants.

They support code analysis, implementation, documentation and review.

Generated suggestions are reviewed and checked against project requirements and automated tests before being incorporated.

## License & Attribution

The original SCC project is distributed under the MIT License.

This port preserves the original author's attribution and applicable license notice.

Original work:
**XxRonaldxX — SCC: Super Cool Calculator**

Port and adaptation:
**Cauê Teles — SCC Fabric**

For the exact license terms, see the `LICENSE` file.

## Disclaimer

SCC Fabric is an unofficial, independently maintained port.

It is not affiliated with, endorsed by or officially supported by XxRonaldxX.

All original concepts, work and applicable intellectual property remain credited to their respective creators.

The purpose of this repository is to document and develop an independent compatibility adaptation for the Fabric platform.
