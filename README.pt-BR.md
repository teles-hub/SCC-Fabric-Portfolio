# SCC Fabric — Port não oficial

**Um port Fabric não oficial e independente de SCC – Super Cool Calculator para Minecraft 1.21.1.**

> **Idioma:** este é o README completo em português. O [README em inglês](README.md) é a versão primária; em caso de divergência, prevalecem o texto em inglês e o código-fonte.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-blue)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Experimental-yellow)](#14-limitações-atuais)

## 1. Sobre o projeto

O SCC Fabric adapta os sistemas não combativos de **SCC – Super Cool Calculator** para o ecossistema de mods Fabric. O mod original foi criado para NeoForge por **XxRonaldxX** e é distribuído sob a licença MIT.

Este repositório contém a publicação do código-fonte dessa adaptação: análise de receitas, logística e redes de cabos, blocos de armazenamento, terminais, infraestrutura de autocrafting e um processador opcional para o Campfire Pot do Cobblemon.

Este é um **port independente e não oficial**. Não é uma versão oficial nem uma continuação autorizada pelo autor original.

## 2. Projeto original e créditos

| Papel | Crédito |
|---|---|
| Projeto original | **SCC – Super Cool Calculator** |
| Criador original | **XxRonaldxX** |
| Plataforma original | NeoForge, Minecraft 1.21.1 |
| Licença original | MIT |
| Port e adaptação para Fabric | **Cauê Teles** |

Links confirmados do trabalho original:

- Código-fonte e rastreador de issues: https://github.com/xXxRonaldxXx/SuperCoolCalculator
- Página no CurseForge indicada pelo projeto original: https://www.curseforge.com/minecraft/mc-mods/scc-super-cool-calculator

Toda a autoria original permanece com XxRonaldxX. Não se alega parceria, autorização pessoal ou suporte oficial.

## 3. Objetivo e escopo

O objetivo é preservar as ideias centrais do mod original enquanto as camadas específicas de plataforma são reconstruídas para o Fabric. O port foca no subconjunto não combativo e **não** é uma restauração completa e um-para-um de todas as funcionalidades do projeto original.

Ambiente-alvo:

- Minecraft **1.21.1**
- Alvo de bytecode Java **21**
- Fabric Loader **0.19.5+** e Fabric API **0.116.18+**
- Cobblemon **1.8.4** / Cobbleverse **1.8.0** como perfil de compatibilidade
- Integrações opcionais sugeridas nos metadados: JEI, EMI, Jade e Accessories

## 4. Sistemas de armazenamento

O código-fonte inclui blocos de armazenamento de itens, melhorias de armazenamento por nível e conectores de armazenamento, com dados de registro e receitas para os níveis disponíveis. O conjunto de dados foi verificado estaticamente quanto à validade dos registros e das referências de receitas.

Transações de armazenamento de itens, fluidos e energia em execução não foram exercitadas em um mundo jogável.

## 5. Infraestrutura de rede e cabos

O port inclui blocos de cabo, um barramento de cabos, conectores, blocos de rede e pontos de acesso sem fio. Os dados publicados usam o identificador registrado `scc:network_cable`, e auditorias estáticas não apontaram referências quebradas de cabos do SCC.

A propagação da rede em execução, o comportamento de transferência e a distribuição de energia não foram validados em jogo.

## 6. Sistema de receitas

O conjunto de dados ativo contém receitas moldadas, sem molde, de melhoria de armazenamento e de fachada. O registro de receitas, o tratamento de ingredientes e os auxiliares de compatibilidade estão incluídos no código-fonte.

JEI e EMI são pontos de integração opcionais; seu comportamento em execução não foi verificado em uma sessão de cliente.

## 7. Autocrafting

O código-fonte inclui agendamento, registro em diário, reserva de materiais, barreiras de salvamento e infraestrutura relacionada à recuperação. Verificações de invariantes foram aplicadas às regras de ordenação e durabilidade durante o desenvolvimento.

Nenhuma tarefa completa de autocrafting foi executada de ponta a ponta em um mundo real do Minecraft.

## 8. Integração com Cobblemon

Um processador de máquina do SCC para o Campfire Pot do Cobblemon é implementado por meio do contrato de processadores do projeto, usando `SavedData` para o estado da operação e reflexão para compatibilidade com a API do Cobblemon. A API do Cobblemon 1.8.4 foi confrontada com o artefato binário durante o desenvolvimento.

Não houve cozimento em jogo nem interação com o Campfire Pot. Permanecem limitações conhecidas no caminho de salvamento: não há garantia de fsync nem de transação atômica, e diários acima do máximo configurado podem ser perdidos ao carregar.

## 9. Interfaces e terminais

Classes de terminal, menu e tela e a cobertura de receitas correspondente estão presentes no código-fonte. A abertura da interface, a sincronização e a interação do jogador não foram testadas com um cliente conectado.

## 10. Stack tecnológica

| Tecnologia | Uso |
|---|---|
| Java 21 | Linguagem e alvo de bytecode |
| Fabric Loader / Fabric API | Execução do mod |
| Fabric Loom / Gradle | Build e gerenciamento de dependências |
| Mapeamentos do Minecraft 1.21.1 | Mapeamentos de desenvolvimento |
| Cobblemon 1.8.4 | Integração opcional com o Campfire Pot |
| JEI / EMI / Jade / Accessories | Pontos opcionais de compatibilidade |
| Python e PowerShell | Ferramentas estáticas de validação usadas no desenvolvimento |

## 11. Desenvolvimento e validação

O desenvolvimento usou worktrees isolados, ferramentas automatizadas de validação estática e verificações de inicialização de servidor sem interface. Os resultados registrados incluem:

- Um build Gradle bem-sucedido a partir do checkout público limpo: `./gradlew build`, com alvo de bytecode Java 21.
- 71 ferramentas automatizadas e 6 auditorias estáticas, todas sem falhas no workspace privado de desenvolvimento.
- Inicialização headless do Fabric até `Done`, sem tags do SCC quebradas nem referências inválidas de cabos.
- Inicialização headless parcial com mods, com um conjunto reduzido; as mensagens restantes foram atribuídas aos data fixers do Cobblemon upstream.

Essas verificações foram feitas no workspace privado de desenvolvimento. Os scripts das ferramentas e as auditorias não estão incluídos nesta árvore pública de código-fonte, portanto quem visita o repositório não pode reproduzi-los a partir deste checkout. Um workflow do GitHub Actions em `.github/workflows/build.yml` executa o `build` público em pushes e pull requests para `main` e envia o JAR remapeado como artefato; as ferramentas privadas continuam sem reprodução lá.

O build público foi executado com JDK 27 disponível na máquina de build, enquanto a configuração do Gradle manteve o alvo de bytecode Java 21; um toolchain JDK 21 não estava instalado localmente.

Trata-se apenas de verificações em tempo de build e sem interface. Elas **não** constituem validação em jogo.

## 12. Desenvolvimento assistido por ferramentas

Ferramentas de programação assistidas por IA, incluindo OpenCode e assistentes de código, foram usadas para apoiar análise de código, implementação, documentação e revisão. As sugestões foram revisadas e conferidas contra os requisitos do projeto e as verificações automatizadas antes de serem incorporadas.

O trabalho continua sendo um port e uma adaptação dirigidos por pessoas; as ferramentas de IA não são creditadas como autoras do projeto original.

## 13. Compilando a partir do código-fonte

Clone o repositório e execute o wrapper do Gradle:

```powershell
.\gradlew.bat build
```

```bash
./gradlew build
```

O script de build define como alvo o bytecode Java 21 e, em `check`, resolve o artefato da API do Cobblemon a partir do Modrinth para verificar compatibilidade binária. Nenhum JAR de terceiros ou dump de bytecode é versionado neste repositório.

### Estrutura do código-fonte

| Pacote | Responsabilidade visível no código-fonte |
|---|---|
| `de.scc.calculator.api` | Pontos públicos usados por integrações opcionais e terminais |
| `de.scc.calculator.calc` | Livro de receitas, fontes de receitas, motor de cálculo, plano de craft e os auxiliares independentes `StackKey`, `ItemGraph`, `AnalysisJob` e `Consumption` |
| `de.scc.calculator.client` | Ciclo de vida do cliente, telas e handler de transferência do JEI |
| `de.scc.calculator.compat` | Integrações opcionais, incluindo o processador do Campfire Pot do Cobblemon |
| `de.scc.calculator.network` | Cabos, armazenamento, menus, fila de tarefas, payloads e lógica de reserva/escrow |
| `de.scc.calculator.platform` | Adaptadores e registros da plataforma Fabric |
| `de.scc.calculator.recipe`, `.registry`, `.data`, `.hooks`, `.mixin` | Adaptadores de receitas, registro, dados de balanceamento, hooks de eventos e o único mixin |

Os recursos em `src/main/resources` contêm metadados do mod, registros, receitas, arquivos de idioma, texturas e fontes incluídas com seus avisos OFL.

### Verificação reproduzível

Os comandos abaixo foram executados a partir de um clone limpo do repositório público. `build` compila todas as fontes e executa `check`; `check` depende de `verifyCobblemonBinaryApi`, que baixa o JAR do Cobblemon 1.8.4 do Modrinth para o cache do Gradle e usa o `javap` do JDK ativo para conferir 14 assinaturas de métodos/campos. O JAR baixado é uma dependência apenas de inspeção e nunca é empacotado no JAR do mod.

```powershell
.\gradlew.bat build --console=plain --no-daemon
```

```bash
./gradlew build --console=plain --no-daemon
```

Resultado observado neste checkout: `BUILD SUCCESSFUL in 1m 57s`, 8 tarefas acionáveis executadas, incluindo `compileJava`, `jar`, `remapJar`, `check` e as 14 verificações de API binária do Cobblemon 1.8.4. A máquina de build tinha JDK 27 instalado; o Gradle ainda compilou com `options.release = 21`. Um workflow do GitHub Actions executa o mesmo build em `main` com JDK 27 (o JDK padrão do runner; o alvo de bytecode permanece Java 21 via `options.release = 21`); a validação em mundo de jogo continua ausente.

## 14. Limitações atuais

- Nenhum cliente real, mundo com jogadores conectados ou sessão de interface foi usado para validação.
- Crafting, transações de armazenamento, rede de cabos, comportamento sem fio e cozimento no Cobblemon permanecem sem teste em jogo.
- As integrações opcionais com JEI, EMI, Jade e Accessories, bem como o perfil completo do modpack Cobbleverse, não foram verificadas em execução.
- As mensagens de data fixer do Cobblemon upstream observadas em uma inicialização parcial com mods são externas ao SCC.
- Implementações Java independentes dos auxiliares `StackKey`, `ItemGraph`, `AnalysisJob` e `Consumption` estão incluídas. Foram escritas a partir dos contratos usados pela árvore pública de código-fonte, sem reutilizar bytecode original ou dumps internos de bytecode, e permitem que um checkout limpo compile.
- O projeto está em desenvolvimento ativo e não é uma versão pronta para produção.

## 15. Licença e atribuição

O projeto SCC original é licenciado sob a MIT License. O aviso de copyright original, `Copyright (c) 2026 XxRonaldxX`, é preservado no arquivo [LICENSE](LICENSE) e nos metadados do mod.

As fontes incluídas são distribuídas sob a SIL Open Font License 1.1; seus avisos estão em `src/main/resources/assets/scc/font/licenses/`.

Port e adaptação: **Cauê Teles**.

## 16. Aviso legal

O SCC Fabric é uma adaptação não oficial mantida de forma independente. Não é afiliado a, endossado por nem oficialmente suportado por XxRonaldxX. Todos os conceitos originais e a propriedade intelectual aplicável permanecem creditados aos respectivos criadores.
