# Changelog

## 1.0.0 (2026-05-05)


### ⚠ BREAKING CHANGES

* **player,world,api:** extract AgentEvent for skill progression events ([#53](https://github.com/Genesara/genesara-engine/issues/53))
* **world,api:** move spawn policy into a SpawnLocationResolver ([#51](https://github.com/Genesara/genesara-engine/issues/51))
* **world,api:** collapse gather + mine into one harvest verb ([#49](https://github.com/Genesara/genesara-engine/issues/49))

### Features

* 12-slot equipment grid (Phase 1 Slice C1) ([647ca3d](https://github.com/Genesara/genesara-engine/commit/647ca3d2866c241c730a6f0410eae0372121e724))
* agent character foundation — attributes, race, get_status ([432573b](https://github.com/Genesara/genesara-engine/commit/432573b30b2b9dd72b635ee9da954af76317fc5d))
* **api,player:** allocate_points MCP tool + typed enum/UUID inputs ([#54](https://github.com/Genesara/genesara-engine/issues/54)) ([41fea26](https://github.com/Genesara/genesara-engine/commit/41fea266c4351a00601f51c12f87d36a28b13fc0))
* **api:** agent management endpoints + Redis activity tracker ([#45](https://github.com/Genesara/genesara-engine/issues/45)) ([1a9cb6a](https://github.com/Genesara/genesara-engine/commit/1a9cb6a353a25814e358e53034022d8b05401416))
* **api:** info log per MCP tool invocation ([#46](https://github.com/Genesara/genesara-engine/issues/46)) ([8363ef2](https://github.com/Genesara/genesara-engine/commit/8363ef2e3a1f3c17799a80e4f8bcc99a611a5b81))
* **api:** player JWT + player-level API token + X-Agent-Id MCP auth ([#44](https://github.com/Genesara/genesara-engine/issues/44)) ([1c87991](https://github.com/Genesara/genesara-engine/commit/1c87991836cf764b3984fe5de2301091117d60fa))
* death system + safe-node checkpoint + respawn (Phase 1 Slice D) ([c812669](https://github.com/Genesara/genesara-engine/commit/c812669c113f820bc33a0691b938009e75490e43))
* gather verb + stackable inventory (Phase 1 Slice 2) ([80aceee](https://github.com/Genesara/genesara-engine/commit/80aceeeb37f200c3a93914c0abbddd304a266271))
* get_map MCP tool + agent map memory (Phase 0 Slice 10) ([7a84568](https://github.com/Genesara/genesara-engine/commit/7a84568f5fb255c368f8c3c367c83b069a2c5b45))
* hide skill catalog — discovery-only get_skills + recommendation-gated equip_skill ([3aa7c68](https://github.com/Genesara/genesara-engine/commit/3aa7c68546d285aa17cebf660ff0da216e36d1e4))
* initial commit of genesara-engine ([af6bbf7](https://github.com/Genesara/genesara-engine/commit/af6bbf7cb5f255517f0b043c61783d87b33eefcb))
* inspect MCP tool with Perception-gated depth (Phase 0 Slice 9) ([c458055](https://github.com/Genesara/genesara-engine/commit/c4580558bd6588b74107a0383016073ea9ad5de5))
* mine MCP tool with symmetric verb partition (Phase 1 Slice A) ([f1328fe](https://github.com/Genesara/genesara-engine/commit/f1328fe378dfe54c90549715fc4f33e18060f313))
* ocean biome + node pvp flag (Phase 0 Slice 7) ([c8c60c3](https://github.com/Genesara/genesara-engine/commit/c8c60c32a22557af32ed0dc3c25d373b91a9cfd1))
* per-item equip requirements (Phase 1 Slice C2) ([173112c](https://github.com/Genesara/genesara-engine/commit/173112c2dd7fbbb28785e3ccb66673b894a05b11))
* Phase 1 Crafting ([#8](https://github.com/Genesara/genesara-engine/issues/8)) ([#48](https://github.com/Genesara/genesara-engine/issues/48)) ([966fdc0](https://github.com/Genesara/genesara-engine/commit/966fdc0b7e328715dd98c3c0152569c93e2041aa))
* Phase 1 Tier-1 Buildings ([#9](https://github.com/Genesara/genesara-engine/issues/9)) ([#47](https://github.com/Genesara/genesara-engine/issues/47)) ([809c700](https://github.com/Genesara/genesara-engine/commit/809c7002e9e29eac791f5ebc6f8d12d9ad41f543))
* rarity + durability foundation (Phase 1 Slice B) ([b9b8a4f](https://github.com/Genesara/genesara-engine/commit/b9b8a4f38efb87dd9957d4319aa086ee2be9e800))
* resource catalog + per-node depletion (Phase 1 Slice 5) ([83e6d93](https://github.com/Genesara/genesara-engine/commit/83e6d933fd212687c721837ab73724e660340fad))
* skills foundation — recommendation-driven slot fill (Phase 1 Slice 6) ([e0ab0d3](https://github.com/Genesara/genesara-engine/commit/e0ab0d366198e063ca463877e500669d6028f8bf))
* starter-nodes admin endpoint (Phase 0 Slice 8) ([321cef6](https://github.com/Genesara/genesara-engine/commit/321cef69efdda5a749ac2e081f67f1fa6b374804))
* survival foundation — hunger/thirst/sleep + consume verb (Phase 1 Slice 3) ([ee8bdd9](https://github.com/Genesara/genesara-engine/commit/ee8bdd94c2c08c24ffe7d1f19a4b3d10a73951f6))
* survival loop closure — drink verb + offline sleep regen (Phase 1 Slice 4) ([21c1469](https://github.com/Genesara/genesara-engine/commit/21c14695719388f95c673cb988b952efcf2b6b25))
* **world,api:** kill-streak item drop on death + ground items + pickup tool ([#57](https://github.com/Genesara/genesara-engine/issues/57)) ([31c037b](https://github.com/Genesara/genesara-engine/commit/31c037b58b7784fe7c5c58167f0e7baffb217121))
* **world,player,api:** VisionRadius helper composes class + Survival + mountain ([#56](https://github.com/Genesara/genesara-engine/issues/56)) ([aff5893](https://github.com/Genesara/genesara-engine/commit/aff58939b16e6db0fdb2f52a860551a4a7157ac7))
* **world:** Strength-driven carry-weight cap on gather + mine ([#42](https://github.com/Genesara/genesara-engine/issues/42)) ([1a69c93](https://github.com/Genesara/genesara-engine/commit/1a69c937d43803fc78c0fe1c177ae3464586b1b2))
* **world:** very-high survival buff tier on stamina regen ([#43](https://github.com/Genesara/genesara-engine/issues/43)) ([dfb1763](https://github.com/Genesara/genesara-engine/commit/dfb1763d597fb5516a9cadf05b712452d7365dad)), closes [#4](https://github.com/Genesara/genesara-engine/issues/4)


### Refactoring

* **api:** jakarta-validation + typed DTOs + ProblemDetail errors ([79a859c](https://github.com/Genesara/genesara-engine/commit/79a859c96c6ca7f135b2048f0375a1ae7ab302f6))
* **player,world,api:** extract AgentEvent for skill progression events ([#53](https://github.com/Genesara/genesara-engine/issues/53)) ([822b2b0](https://github.com/Genesara/genesara-engine/commit/822b2b06847a7289383e174e6db01512ec91334d))
* rename agenticrpg → genesara across packages, plugins, and config ([21cd4b6](https://github.com/Genesara/genesara-engine/commit/21cd4b62071fcc8303880a774a6ce87468c4efb6))
* **world,api:** collapse gather + mine into one harvest verb ([#49](https://github.com/Genesara/genesara-engine/issues/49)) ([99a8fc7](https://github.com/Genesara/genesara-engine/commit/99a8fc7b6598d3b790ab235b7b027d662fe3af53))
* **world,api:** move spawn policy into a SpawnLocationResolver ([#51](https://github.com/Genesara/genesara-engine/issues/51)) ([5cae81f](https://github.com/Genesara/genesara-engine/commit/5cae81fcee6ad526e8c62186ababe52e580be414))
* **world:** extract skill progression into a single module ([#50](https://github.com/Genesara/genesara-engine/issues/50)) ([26c2dd8](https://github.com/Genesara/genesara-engine/commit/26c2dd80c4e6ee10b8b61e67089fc39c761e8ad3))


### Documentation

* codify mechanics specification, workflow, and roadmap ([2572b18](https://github.com/Genesara/genesara-engine/commit/2572b18ab6258991f521461d11bf238d990b81d7))
* flip death-system roadmap checkboxes after Phase 1 Slice D ([771f071](https://github.com/Genesara/genesara-engine/commit/771f0711056dfe7c8acd5542e31009786526b449))
* flip equip-requirements roadmap checkbox after Phase 1 Slice C2 ([757cafe](https://github.com/Genesara/genesara-engine/commit/757cafe8bdbf31d63774e43762c71a259f47f737))
* flip equipment-grid roadmap checkboxes after Phase 1 Slice C1 ([5a4ef26](https://github.com/Genesara/genesara-engine/commit/5a4ef265a22279c53bb9ed99bc07cb30d3812c1f))
* flip mine roadmap checkbox after Phase 1 Slice A ([161a249](https://github.com/Genesara/genesara-engine/commit/161a249c54c9f633b6afc2e1a004600ef5fcdf34))
* flip rarity + durability roadmap checkboxes after Phase 1 Slice B ([4f9f2b7](https://github.com/Genesara/genesara-engine/commit/4f9f2b72df465a4670862f9ed4b7e02012d104aa))
* migrate roadmap to GitHub issues + slim CLAUDE.md ([a942ce6](https://github.com/Genesara/genesara-engine/commit/a942ce6e735503e7304dbd81553690261ffbe1be))
* refresh Phase 1 roadmap after slice 5 ([b64a9d8](https://github.com/Genesara/genesara-engine/commit/b64a9d85627b472f618794d8e6007076662f90e1))
