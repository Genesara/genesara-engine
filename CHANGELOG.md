# Changelog

## [1.1.0](https://github.com/Genesara/genesara-engine/compare/v1.0.0...v1.1.0) (2026-06-01)


### Features

* **admin,api:** admin audit log substrate ([#198](https://github.com/Genesara/genesara-engine/issues/198)) ([#212](https://github.com/Genesara/genesara-engine/issues/212)) ([f82446b](https://github.com/Genesara/genesara-engine/commit/f82446bbba1414e8c032dfe8eeaac1b6e2434bc4))
* **api,player:** admin agent edit — meta (XP/level/attributes/skills/perks/class) ([#203](https://github.com/Genesara/genesara-engine/issues/203)) ([#218](https://github.com/Genesara/genesara-engine/issues/218)) ([6f29fd8](https://github.com/Genesara/genesara-engine/commit/6f29fd8829579d68c79226b4ac21ef0d1556673d))
* **api,world,player:** admin sentinel + building CRUD ([#200](https://github.com/Genesara/genesara-engine/issues/200)) ([#216](https://github.com/Genesara/genesara-engine/issues/216)) ([05a52ee](https://github.com/Genesara/genesara-engine/commit/05a52ee7eb29cd370244646c898f158fe5b27ef7))
* **api,world:** admin agent edit — inventory + equipment instances ([#205](https://github.com/Genesara/genesara-engine/issues/205)) ([#215](https://github.com/Genesara/genesara-engine/issues/215)) ([e04b66b](https://github.com/Genesara/genesara-engine/commit/e04b66b9726f998bf467872110bee85ac8816ea8))
* **api:** admin agent social editor — authority/fame/outlaw/relationships ([#206](https://github.com/Genesara/genesara-engine/issues/206)) ([#219](https://github.com/Genesara/genesara-engine/issues/219)) ([5e3d1f9](https://github.com/Genesara/genesara-engine/commit/5e3d1f98d9448ed77b6d3df5c921a0553757ac20))
* **api:** admin live world feed — SSE + Redis replay ([#202](https://github.com/Genesara/genesara-engine/issues/202)) ([#213](https://github.com/Genesara/genesara-engine/issues/213)) ([5c9dbc2](https://github.com/Genesara/genesara-engine/commit/5c9dbc270a0ba9eb369c1cde94336252540b29a1))
* **api:** dashboard backend — public stats + sliced agent detail + SSE event tail ([#189](https://github.com/Genesara/genesara-engine/issues/189)) ([c917c61](https://github.com/Genesara/genesara-engine/commit/c917c6175568519e888e13db94ddd18ea750e94d))
* clan & faction system ([#22](https://github.com/Genesara/genesara-engine/issues/22)) ([#222](https://github.com/Genesara/genesara-engine/issues/222)) ([b7b3538](https://github.com/Genesara/genesara-engine/commit/b7b35385aa1060b0415b6a70eebc7e1b1a409160))
* **player,world,api:** phase 2 relationships, authority + fame ([#14](https://github.com/Genesara/genesara-engine/issues/14)) ([#186](https://github.com/Genesara/genesara-engine/issues/186)) ([1f2557b](https://github.com/Genesara/genesara-engine/commit/1f2557bc0287de144472c167b531911280fbf584))
* **world,api,player:** cultivated resources via plant/tend/harvest on FARM_PLOT ([#175](https://github.com/Genesara/genesara-engine/issues/175)) ([f84946a](https://github.com/Genesara/genesara-engine/commit/f84946ae53ddd4a731310e1f7dcd750797c94c2f))
* **world,api,player:** phase 2 PvP — outlaw state machine + green-zone enforcement ([#17](https://github.com/Genesara/genesara-engine/issues/17)) ([#196](https://github.com/Genesara/genesara-engine/issues/196)) ([3fc542e](https://github.com/Genesara/genesara-engine/commit/3fc542e1109e96b564ef8fb20dbfcb62f993004b))
* **world,api:** admin agent body edit — gauges/teleport/safe-node/respawn ([#204](https://github.com/Genesara/genesara-engine/issues/204)) ([#217](https://github.com/Genesara/genesara-engine/issues/217)) ([3c085e1](https://github.com/Genesara/genesara-engine/commit/3c085e108340ff9c0285bc5568a996268789848d))
* **world,api:** admin chest CRUD over chest-shaped buildings ([#220](https://github.com/Genesara/genesara-engine/issues/220)) ([70462b8](https://github.com/Genesara/genesara-engine/commit/70462b89d9eedd875128a9d8745cf68be8a1c748)), closes [#208](https://github.com/Genesara/genesara-engine/issues/208)
* **world,api:** admin NPC instance CRUD (spawn/move/heal/kill) ([#214](https://github.com/Genesara/genesara-engine/issues/214)) ([012b52c](https://github.com/Genesara/genesara-engine/commit/012b52c4ae6b6a657084031f0286713186506bf1))
* **world,api:** admin NPC zones — per-region/per-node spawn overrides ([#207](https://github.com/Genesara/genesara-engine/issues/207)) ([#221](https://github.com/Genesara/genesara-engine/issues/221)) ([cf30dd0](https://github.com/Genesara/genesara-engine/commit/cf30dd089464666d07f6059ea0f3e9d5ce92ab88))
* **world,api:** defensive cluster + extraction infrastructure ([#178](https://github.com/Genesara/genesara-engine/issues/178)) ([83b1e79](https://github.com/Genesara/genesara-engine/commit/83b1e79c31cfcaaeeb6214703932cbb298216bdb))
* **world,api:** height-aware line-of-sight fog of war ([#55](https://github.com/Genesara/genesara-engine/issues/55)) ([#181](https://github.com/Genesara/genesara-engine/issues/181)) ([64ccac8](https://github.com/Genesara/genesara-engine/commit/64ccac87af76a31f55517269c551a1d9f4d5d39e))
* **world,api:** mounts — initial ([#21](https://github.com/Genesara/genesara-engine/issues/21)) ([#191](https://github.com/Genesara/genesara-engine/issues/191)) ([001a6e8](https://github.com/Genesara/genesara-engine/commit/001a6e8c458b9f3730ba342fa1e0022d9229c0f0))
* **world,api:** party — invite/respond/leave/kick + XP split + formation buff ([#16](https://github.com/Genesara/genesara-engine/issues/16)) ([#194](https://github.com/Genesara/genesara-engine/issues/194)) ([370c4e2](https://github.com/Genesara/genesara-engine/commit/370c4e2a9bb48abd4c56d937bdfb6b2b5451fbad))
* **world,api:** phase 2 tier-A NPCs + wire-prefixed entity ids ([#182](https://github.com/Genesara/genesara-engine/issues/182)) ([eef67c6](https://github.com/Genesara/genesara-engine/commit/eef67c6d950ecf295bd30f02b2e05a17bba18864))
* **world,api:** trade v2 — instance trading ([#190](https://github.com/Genesara/genesara-engine/issues/190)) ([#193](https://github.com/Genesara/genesara-engine/issues/193)) ([18cc5bb](https://github.com/Genesara/genesara-engine/commit/18cc5bbf86c7a2232cb54a7c73ac22115b8cbe92))
* **world:** fauna expansion content — 25 fauna, 11 materials + 21 produced items, 2 buildings ([#184](https://github.com/Genesara/genesara-engine/issues/184)) ([0107483](https://github.com/Genesara/genesara-engine/commit/0107483087f8ce38e82ac05577d1daffa386dfc8))
* **world:** fauna expansion recipes — 34 new + 13 equipment items ([#185](https://github.com/Genesara/genesara-engine/issues/185)) ([0cc9e1a](https://github.com/Genesara/genesara-engine/commit/0cc9e1a92fe3314e6448567202c259d9a5e1496b))
* **world:** phase 2 tier-2 buildings + bartering skill ([#177](https://github.com/Genesara/genesara-engine/issues/177)) ([bb86e19](https://github.com/Genesara/genesara-engine/commit/bb86e1912f866ce66a5ef58ca6ead3711dc1871f))


### Bug Fixes

* **api:** return 401 (not 403) for unauthenticated bearer/MCP requests ([#223](https://github.com/Genesara/genesara-engine/issues/223)) ([56fef41](https://github.com/Genesara/genesara-engine/commit/56fef41fbdcb9bd8f621e4b2b1494e6a52d0554d))
* **api:** route AgentAttackedNpc and NpcDied to the agent event stream ([#195](https://github.com/Genesara/genesara-engine/issues/195)) ([c23afa6](https://github.com/Genesara/genesara-engine/commit/c23afa6eaf244ee3a4b10ad28e81733b67a1fa28))
* **world,player,api:** playtest s10 findings — NPC density + death signaling + move threat hint ([#197](https://github.com/Genesara/genesara-engine/issues/197)) ([a6da11c](https://github.com/Genesara/genesara-engine/commit/a6da11c6f9919aa4d4e2fc7dd2e6ab8a3ed92028))


### Refactoring

* **world,api:** unify per-instance items into agent_item_instances ([#180](https://github.com/Genesara/genesara-engine/issues/180)) ([445ce19](https://github.com/Genesara/genesara-engine/commit/445ce19bf8edbbb2b078557f4cd0a7e955104a20))
* **world,player:** fauna expansion foundation — HUNTING xp, flee BFS, mob-keyed loot tables ([#183](https://github.com/Genesara/genesara-engine/issues/183)) ([ffc7be5](https://github.com/Genesara/genesara-engine/commit/ffc7be5d6cda41ce2b7233b5a80c8e9f42e8795e))
* **world:** address PR [#187](https://github.com/Genesara/genesara-engine/issues/187) honest deviations (a)+(b)+(c)+(d) ([#188](https://github.com/Genesara/genesara-engine/issues/188)) ([4fb411f](https://github.com/Genesara/genesara-engine/commit/4fb411fb880898147fde2cfd88a07312a9fec97d))
* **world:** split into 5 zone modules + umbrella (ADR 0003) ([#187](https://github.com/Genesara/genesara-engine/issues/187)) ([dd1eaf3](https://github.com/Genesara/genesara-engine/commit/dd1eaf3926c654ed63b77d09b909100247633883))


### Documentation

* **admin:** resolve open design questions ([#199](https://github.com/Genesara/genesara-engine/issues/199)) ([#211](https://github.com/Genesara/genesara-engine/issues/211)) ([37ea34e](https://github.com/Genesara/genesara-engine/commit/37ea34ebf1e3f1296a1ed7bdeddb77dacfc32a5b))

## 1.0.0 (2026-05-12)


### ⚠ BREAKING CHANGES

* **player,world,api:** extract AgentEvent for skill progression events ([#53](https://github.com/Genesara/genesara-engine/issues/53))
* **world,api:** move spawn policy into a SpawnLocationResolver ([#51](https://github.com/Genesara/genesara-engine/issues/51))
* **world,api:** collapse gather + mine into one harvest verb ([#49](https://github.com/Genesara/genesara-engine/issues/49))

### Features

* 12-slot equipment grid (Phase 1 Slice C1) ([647ca3d](https://github.com/Genesara/genesara-engine/commit/647ca3d2866c241c730a6f0410eae0372121e724))
* agent character foundation — attributes, race, get_status ([432573b](https://github.com/Genesara/genesara-engine/commit/432573b30b2b9dd72b635ee9da954af76317fc5d))
* **api,ci:** publish MCP tool schema as a release asset ([#174](https://github.com/Genesara/genesara-engine/issues/174)) ([e7335af](https://github.com/Genesara/genesara-engine/commit/e7335af59c157e26d2eceb4a4cb59db24cc1db85))
* **api,player:** allocate_points MCP tool + typed enum/UUID inputs ([#54](https://github.com/Genesara/genesara-engine/issues/54)) ([41fea26](https://github.com/Genesara/genesara-engine/commit/41fea266c4351a00601f51c12f87d36a28b13fc0))
* **api:** add get_recipes tool with hybrid per-agent recipe discovery ([#148](https://github.com/Genesara/genesara-engine/issues/148)) ([347babd](https://github.com/Genesara/genesara-engine/commit/347babddf920b970f5df81d92c92d57fbcb0a8e8)), closes [#146](https://github.com/Genesara/genesara-engine/issues/146)
* **api:** agent management endpoints + Redis activity tracker ([#45](https://github.com/Genesara/genesara-engine/issues/45)) ([1a9cb6a](https://github.com/Genesara/genesara-engine/commit/1a9cb6a353a25814e358e53034022d8b05401416))
* **api:** consolidate MCP read tools into get_status + get_loadout ([#61](https://github.com/Genesara/genesara-engine/issues/61)) ([8c7c1c1](https://github.com/Genesara/genesara-engine/commit/8c7c1c16bc954f2f1b5a6ffb1b4805beeab22b78))
* **api:** expose co-located agents on look_around.currentNode ([#145](https://github.com/Genesara/genesara-engine/issues/145)) ([f8b3719](https://github.com/Genesara/genesara-engine/commit/f8b37191038f808213a7d8452d6e5d45d9bf19ae))
* **api:** info log per MCP tool invocation ([#46](https://github.com/Genesara/genesara-engine/issues/46)) ([8363ef2](https://github.com/Genesara/genesara-engine/commit/8363ef2e3a1f3c17799a80e4f8bcc99a611a5b81))
* **api:** inspect tool projects equipment block (closes [#156](https://github.com/Genesara/genesara-engine/issues/156)) ([#158](https://github.com/Genesara/genesara-engine/issues/158)) ([b790866](https://github.com/Genesara/genesara-engine/commit/b79086616ed4311f56b2df6a3d54521784b2ece4))
* **api:** player JWT + player-level API token + X-Agent-Id MCP auth ([#44](https://github.com/Genesara/genesara-engine/issues/44)) ([1c87991](https://github.com/Genesara/genesara-engine/commit/1c87991836cf764b3984fe5de2301091117d60fa))
* death system + safe-node checkpoint + respawn (Phase 1 Slice D) ([c812669](https://github.com/Genesara/genesara-engine/commit/c812669c113f820bc33a0691b938009e75490e43))
* **engine,world:** per-world WorldState + per-world tick counter ([#78](https://github.com/Genesara/genesara-engine/issues/78)) ([#84](https://github.com/Genesara/genesara-engine/issues/84)) ([368e850](https://github.com/Genesara/genesara-engine/commit/368e85038ede2f792b1315496c62d000585bbced))
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
* **player,api:** perk selection flow with select_perk tool ([#70](https://github.com/Genesara/genesara-engine/issues/70)) ([79b7650](https://github.com/Genesara/genesara-engine/commit/79b76503af2cf6a1fa49a578f81450f5c5c760f3))
* **player,api:** skill+perk catalog v1 ([#68](https://github.com/Genesara/genesara-engine/issues/68)) ([#75](https://github.com/Genesara/genesara-engine/issues/75)) ([29a453c](https://github.com/Genesara/genesara-engine/commit/29a453cbda86114ecc07f6f502e7a36199d14446))
* **player,world,api:** active-ability perk system + use_ability tool ([#67](https://github.com/Genesara/genesara-engine/issues/67)) ([#74](https://github.com/Genesara/genesara-engine/issues/74)) ([513c99c](https://github.com/Genesara/genesara-engine/commit/513c99c67468cd3aa9fc981cfbdfc9c90db6ea7f))
* **player,world,api:** level-10 class choice + select_class ([#33](https://github.com/Genesara/genesara-engine/issues/33)) ([#101](https://github.com/Genesara/genesara-engine/issues/101)) ([d9ef096](https://github.com/Genesara/genesara-engine/commit/d9ef09685ed474f0d6d696518bd638b422a2a1f4))
* **player,world,api:** level-50 class evolution + select_evolution ([#34](https://github.com/Genesara/genesara-engine/issues/34)) ([#103](https://github.com/Genesara/genesara-engine/issues/103)) ([6b70e0a](https://github.com/Genesara/genesara-engine/commit/6b70e0a6ba3256409f0be9c0cb10041cee2c639e))
* **player,world:** class catalog v1 + AgentClass surgery ([#32](https://github.com/Genesara/genesara-engine/issues/32)) ([#94](https://github.com/Genesara/genesara-engine/issues/94)) ([ab44292](https://github.com/Genesara/genesara-engine/commit/ab442929a811f14130d3f060ca5e37831ed8671d))
* **player,world:** emit agent.xp_gained on character XP grants ([#168](https://github.com/Genesara/genesara-engine/issues/168)) ([450af2b](https://github.com/Genesara/genesara-engine/commit/450af2b8e511c565b00edf4915a1a985b99f63a0))
* **player,world:** passive-aura aggregator + flat post-scaling damage ([#65](https://github.com/Genesara/genesara-engine/issues/65)) ([#73](https://github.com/Genesara/genesara-engine/issues/73)) ([12d416c](https://github.com/Genesara/genesara-engine/commit/12d416c01185e1fbd094f66ae2067f51c5694739))
* **player,world:** per-level skill scaling + Modifier perk ([#66](https://github.com/Genesara/genesara-engine/issues/66)) ([#71](https://github.com/Genesara/genesara-engine/issues/71)) ([bf22d2f](https://github.com/Genesara/genesara-engine/commit/bf22d2ff5874acd5418fe5471a9c67934f0bf662))
* **player,world:** triggered-passive perk system + reducer hooks ([#64](https://github.com/Genesara/genesara-engine/issues/64)) ([#72](https://github.com/Genesara/genesara-engine/issues/72)) ([008746a](https://github.com/Genesara/genesara-engine/commit/008746a31ef667969efa14f6aab549a0b25dc000))
* **player:** perk data shape + SWORD canary perks ([#62](https://github.com/Genesara/genesara-engine/issues/62)) ([#69](https://github.com/Genesara/genesara-engine/issues/69)) ([504aa11](https://github.com/Genesara/genesara-engine/commit/504aa11b6912d537307d11fe916a2cbc6ab5b3b8))
* rarity + durability foundation (Phase 1 Slice B) ([b9b8a4f](https://github.com/Genesara/genesara-engine/commit/b9b8a4f38efb87dd9957d4319aa086ee2be9e800))
* resource catalog + per-node depletion (Phase 1 Slice 5) ([83e6d93](https://github.com/Genesara/genesara-engine/commit/83e6d933fd212687c721837ab73724e660340fad))
* skills foundation — recommendation-driven slot fill (Phase 1 Slice 6) ([e0ab0d3](https://github.com/Genesara/genesara-engine/commit/e0ab0d366198e063ca463877e500669d6028f8bf))
* starter-nodes admin endpoint (Phase 0 Slice 8) ([321cef6](https://github.com/Genesara/genesara-engine/commit/321cef69efdda5a749ac2e081f67f1fa6b374804))
* survival foundation — hunger/thirst/sleep + consume verb (Phase 1 Slice 3) ([ee8bdd9](https://github.com/Genesara/genesara-engine/commit/ee8bdd94c2c08c24ffe7d1f19a4b3d10a73951f6))
* survival loop closure — drink verb + offline sleep regen (Phase 1 Slice 4) ([21c1469](https://github.com/Genesara/genesara-engine/commit/21c14695719388f95c673cb988b952efcf2b6b25))
* **world,api:** add say MCP tool for proximity chat ([#172](https://github.com/Genesara/genesara-engine/issues/172)) ([cf6fac8](https://github.com/Genesara/genesara-engine/commit/cf6fac86a91be686bd7a31628ea113da9e5e7492))
* **world,api:** add two-step barter trade with trust gate ([#173](https://github.com/Genesara/genesara-engine/issues/173)) ([314febc](https://github.com/Genesara/genesara-engine/commit/314febc637371ffdeaf1f6953c695a91737e2be7))
* **world,api:** emit building.progressed and building.constructed events ([#167](https://github.com/Genesara/genesara-engine/issues/167)) ([52a29ac](https://github.com/Genesara/genesara-engine/commit/52a29ac90d890e29f72e89cef7c8b560da33faab))
* **world,api:** equipment definition — Slices 2–6 of [#147](https://github.com/Genesara/genesara-engine/issues/147) ([#155](https://github.com/Genesara/genesara-engine/issues/155)) ([d5a9867](https://github.com/Genesara/genesara-engine/commit/d5a9867e85482b2814dee606d5f10f027d4521f0))
* **world,api:** include staminaSpent in agent.moved event payload ([#165](https://github.com/Genesara/genesara-engine/issues/165)) ([cfa4eb0](https://github.com/Genesara/genesara-engine/commit/cfa4eb01014d29248ed34d5a2a3a5e2b20db0768))
* **world,api:** kill-streak item drop on death + ground items + pickup tool ([#57](https://github.com/Genesara/genesara-engine/issues/57)) ([31c037b](https://github.com/Genesara/genesara-engine/commit/31c037b58b7784fe7c5c58167f0e7baffb217121))
* **world,api:** MVP attack verb + multi-event reducers + weapon range ([#58](https://github.com/Genesara/genesara-engine/issues/58)) ([2f3b29e](https://github.com/Genesara/genesara-engine/commit/2f3b29ebc4e63acebde32005e59a7a48f3c18914))
* **world,api:** rarity scales weapon damage and durability (closes [#157](https://github.com/Genesara/genesara-engine/issues/157)) ([#159](https://github.com/Genesara/genesara-engine/issues/159)) ([fa069fa](https://github.com/Genesara/genesara-engine/commit/fa069fa70f456a4bbff9af46a05c1f64c5f87986))
* **world,api:** redis invalidation bus for cross-pod MCP push + static config ([#83](https://github.com/Genesara/genesara-engine/issues/83)) ([#90](https://github.com/Genesara/genesara-engine/issues/90)) ([6dbd976](https://github.com/Genesara/genesara-engine/commit/6dbd9767118d95dcf84883ecf7aee1af813371be))
* **world,api:** redis-per-world command queue with polymorphic serialization ([#82](https://github.com/Genesara/genesara-engine/issues/82)) ([#89](https://github.com/Genesara/genesara-engine/issues/89)) ([36e858c](https://github.com/Genesara/genesara-engine/commit/36e858c7859df9d2158fbf0134a48a41f9523a5c))
* **world,api:** typed ResourceItemId enum at terrains.yaml + harvest boundary ([#59](https://github.com/Genesara/genesara-engine/issues/59)) ([bb880d1](https://github.com/Genesara/genesara-engine/commit/bb880d10660f22e575f9ad0f6c30c70edee4f1aa))
* **world,player,api:** VisionRadius helper composes class + Survival + mountain ([#56](https://github.com/Genesara/genesara-engine/issues/56)) ([aff5893](https://github.com/Genesara/genesara-engine/commit/aff58939b16e6db0fdb2f52a860551a4a7157ac7))
* **world:** equipment bonus data model + armor-def mitigation (Slice 1 of [#147](https://github.com/Genesara/genesara-engine/issues/147)) ([#150](https://github.com/Genesara/genesara-engine/issues/150)) ([a4f0125](https://github.com/Genesara/genesara-engine/commit/a4f0125100c07732204f9a5cb288b96cfb64facf))
* **world:** gate survival drains to every 15th tick (~2h depletion) ([bbe7541](https://github.com/Genesara/genesara-engine/commit/bbe754197c9f80a6e11b93d84c9aa7e99306c6b4))
* **world:** parallel per-world tick fan-out via Dispatchers.IO ([#81](https://github.com/Genesara/genesara-engine/issues/81)) ([#87](https://github.com/Genesara/genesara-engine/issues/87)) ([038ddb2](https://github.com/Genesara/genesara-engine/commit/038ddb223850abea98c76c6c7f442bbb99f65bf2))
* **world:** per-world Redis lease with fenced writes ([#80](https://github.com/Genesara/genesara-engine/issues/80)) ([#86](https://github.com/Genesara/genesara-engine/issues/86)) ([5380fe2](https://github.com/Genesara/genesara-engine/commit/5380fe2d2cf85c97d22cece362d3c3e57242b984))
* **world:** redis stores for cooldowns, kill streaks, pending scales ([#79](https://github.com/Genesara/genesara-engine/issues/79)) ([#85](https://github.com/Genesara/genesara-engine/issues/85)) ([f0f3163](https://github.com/Genesara/genesara-engine/commit/f0f3163ccb2f8594d97267a163844334e64ef3d5))
* **world:** silent per-agent behavior tracker ([#31](https://github.com/Genesara/genesara-engine/issues/31)) ([#92](https://github.com/Genesara/genesara-engine/issues/92)) ([eeb1ca1](https://github.com/Genesara/genesara-engine/commit/eeb1ca16b0ec6d9bcd620ba8edf1fd75056be445))
* **world:** Strength-driven carry-weight cap on gather + mine ([#42](https://github.com/Genesara/genesara-engine/issues/42)) ([1a69c93](https://github.com/Genesara/genesara-engine/commit/1a69c937d43803fc78c0fe1c177ae3464586b1b2))
* **world:** very-high survival buff tier on stamina regen ([#43](https://github.com/Genesara/genesara-engine/issues/43)) ([dfb1763](https://github.com/Genesara/genesara-engine/commit/dfb1763d597fb5516a9cadf05b712452d7365dad)), closes [#4](https://github.com/Genesara/genesara-engine/issues/4)
* **world:** wire LEATHERWORKING, TAILORING, JEWELRYCRAFTING into craft XP ([#161](https://github.com/Genesara/genesara-engine/issues/161)) ([ab611ad](https://github.com/Genesara/genesara-engine/commit/ab611ad779354ae7f54246cc0d2eb0de7654a3f5))


### Bug Fixes

* align move/unspawn rejection with NotInWorld, add NO_OP for allocate_points ([#134](https://github.com/Genesara/genesara-engine/issues/134)) ([c3e507b](https://github.com/Genesara/genesara-engine/commit/c3e507b6b7336e1df592a70f005e1e27ada66534))
* **api:** advertise event-stream resource via agent://self/events ([#140](https://github.com/Genesara/genesara-engine/issues/140)) ([5599304](https://github.com/Genesara/genesara-engine/commit/55993040973d3d85aa2230837a030bd9adc16d50))
* **api:** align get_status tick with event clock and add safe-node projection ([#144](https://github.com/Genesara/genesara-engine/issues/144)) ([ebf7808](https://github.com/Genesara/genesara-engine/commit/ebf7808e98721dab299c3563d88cae174400b6de)), closes [#115](https://github.com/Genesara/genesara-engine/issues/115) [#117](https://github.com/Genesara/genesara-engine/issues/117) [#130](https://github.com/Genesara/genesara-engine/issues/130)
* **api:** align MCP UUID @ToolParam binder, tighten playtest tick, fix redis listener lifecycle ([#132](https://github.com/Genesara/genesara-engine/issues/132)) ([fd6f98b](https://github.com/Genesara/genesara-engine/commit/fd6f98ba9806ef194f71dbfe555b4dc1ca1510d1))
* **api:** case-insensitive enum binding on @ToolParam input ([#133](https://github.com/Genesara/genesara-engine/issues/133)) ([618347e](https://github.com/Genesara/genesara-engine/commit/618347ef7ba11ab05fed317bb35797e718d8961e))
* **api:** populate inspect(AGENT,BUILDING) band/effect/owner fields ([#169](https://github.com/Genesara/genesara-engine/issues/169)) ([973d7e8](https://github.com/Genesara/genesara-engine/commit/973d7e8ee7bac89e6b7d421ba2b2f10818ec6fdc))
* **api:** recover gracefully from stale MCP session ids ([#163](https://github.com/Genesara/genesara-engine/issues/163)) ([26754b2](https://github.com/Genesara/genesara-engine/commit/26754b2a3fadc0293a9750acae810ac9f8a3a732))
* **api:** return structured errors for missing required params and unknown enum values ([#139](https://github.com/Genesara/genesara-engine/issues/139)) ([448c0a9](https://github.com/Genesara/genesara-engine/commit/448c0a9ff37e4479eb53675ca0dc6db5aa15b20a)), closes [#106](https://github.com/Genesara/genesara-engine/issues/106) [#129](https://github.com/Genesara/genesara-engine/issues/129)
* **api:** structured rejections for enum + missing-param binder errors ([#166](https://github.com/Genesara/genesara-engine/issues/166)) ([2e66650](https://github.com/Genesara/genesara-engine/commit/2e66650bb02078ba1d2d39d2bea270812d6f0bc3))
* **api:** wire missing WorldEvent dispatchers onto the agent event stream ([#137](https://github.com/Genesara/genesara-engine/issues/137)) ([83fb5c8](https://github.com/Genesara/genesara-engine/commit/83fb5c844d1a3cfc0e89a8e4dff5a2b916609541))
* bump move stamina cost, rename look_around adjacent, register select_perk ([#135](https://github.com/Genesara/genesara-engine/issues/135)) ([7980753](https://github.com/Genesara/genesara-engine/commit/79807532413ba73259d065a5f736890d4ed8ea2d))
* **player,world,api:** refresh body pool maxima after allocate_points ([#136](https://github.com/Genesara/genesara-engine/issues/136)) ([e2d725f](https://github.com/Genesara/genesara-engine/commit/e2d725f6db8d167b049956b43e7852700ccd3e47))
* **player:** dedup skill.recommended within cooldown window per skill ([#164](https://github.com/Genesara/genesara-engine/issues/164)) ([25f680d](https://github.com/Genesara/genesara-engine/commit/25f680d3cf70c5eaacc7b57c797428b521a8b820))
* **world:** align agent.xp_gained.tick with the world clock ([#170](https://github.com/Genesara/genesara-engine/issues/170)) ([1a35de0](https://github.com/Genesara/genesara-engine/commit/1a35de071838774695d9110bc795a7953b185541))
* **world:** grant character XP from harvest and consume ([#138](https://github.com/Genesara/genesara-engine/issues/138)) ([2eb61e3](https://github.com/Genesara/genesara-engine/commit/2eb61e3c3c4370d5e365551716fbee1df42e5fa5))
* **world:** grant skill XP on consume of items with a harvestSkill ([#141](https://github.com/Genesara/genesara-engine/issues/141)) ([5ec54d6](https://github.com/Genesara/genesara-engine/commit/5ec54d608e0e72db93734ac56376b49947989d6d)), closes [#120](https://github.com/Genesara/genesara-engine/issues/120)
* **world:** reject duplicate building of same type at same node ([#171](https://github.com/Genesara/genesara-engine/issues/171)) ([317932e](https://github.com/Genesara/genesara-engine/commit/317932e8fd0d1024c727000a271b78fb39cf3ad0))
* **world:** resume persisted body when spawning an offline agent ([#143](https://github.com/Genesara/genesara-engine/issues/143)) ([10f6365](https://github.com/Genesara/genesara-engine/commit/10f63651034931b10f3a98a4fea6820d689e78da)), closes [#116](https://github.com/Genesara/genesara-engine/issues/116)


### Refactoring

* **api:** jakarta-validation + typed DTOs + ProblemDetail errors ([79a859c](https://github.com/Genesara/genesara-engine/commit/79a859c96c6ca7f135b2048f0375a1ae7ab302f6))
* **api:** replace MCP *Request wrappers with @ToolParam-annotated method params ([b850522](https://github.com/Genesara/genesara-engine/commit/b8505221463eb8edb6d147818a343d6753e954fa))
* **player,world,api:** extract AgentEvent for skill progression events ([#53](https://github.com/Genesara/genesara-engine/issues/53)) ([822b2b0](https://github.com/Genesara/genesara-engine/commit/822b2b06847a7289383e174e6db01512ec91334d))
* **player:** shard skills.yaml + classes.yaml into per-domain files ([#160](https://github.com/Genesara/genesara-engine/issues/160)) ([0317466](https://github.com/Genesara/genesara-engine/commit/0317466116246de23ebe86c95b4fa069b38fb584))
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
* pin equipment-definition design decisions for [#147](https://github.com/Genesara/genesara-engine/issues/147) ([#149](https://github.com/Genesara/genesara-engine/issues/149)) ([7ac09d4](https://github.com/Genesara/genesara-engine/commit/7ac09d4c00db69515210b938c40e88458cf6f50d))
* refresh Phase 1 roadmap after slice 5 ([b64a9d8](https://github.com/Genesara/genesara-engine/commit/b64a9d85627b472f618794d8e6007076662f90e1))
* **shard-readiness:** tick Step 4 ([#81](https://github.com/Genesara/genesara-engine/issues/81)) and advance current step to Step 5 ([#82](https://github.com/Genesara/genesara-engine/issues/82)) ([#88](https://github.com/Genesara/genesara-engine/issues/88)) ([d73b126](https://github.com/Genesara/genesara-engine/commit/d73b126ca158238eb223ea1a9117b7dc07e2d0fa))
* **shard-readiness:** tick Steps 5 ([#82](https://github.com/Genesara/genesara-engine/issues/82)) and 6 ([#83](https://github.com/Genesara/genesara-engine/issues/83)); mark initiative complete ([#91](https://github.com/Genesara/genesara-engine/issues/91)) ([ac0b8a1](https://github.com/Genesara/genesara-engine/commit/ac0b8a157e21ba7d63fcc6376d63677503bea6e8))
* **skill-feature:** record [#161](https://github.com/Genesara/genesara-engine/issues/161) wiring of LEATHERWORKING, TAILORING, JEWELRYCRAFTING, DAGGER ([#162](https://github.com/Genesara/genesara-engine/issues/162)) ([49f764a](https://github.com/Genesara/genesara-engine/commit/49f764a86ee9847949123b08383fba5cb3e10c8d))
* **skill-feature:** tick [#34](https://github.com/Genesara/genesara-engine/issues/34)/[#38](https://github.com/Genesara/genesara-engine/issues/38) cross-issue coordination after step 8 merge ([#104](https://github.com/Genesara/genesara-engine/issues/104)) ([f8f535a](https://github.com/Genesara/genesara-engine/commit/f8f535ad5f64aa820fd8a072603ed899ea3b2ea8))
* **skill-feature:** tick Step 3d ([#67](https://github.com/Genesara/genesara-engine/issues/67)) and advance current step to Step 4 ([#68](https://github.com/Genesara/genesara-engine/issues/68)) ([95d4ad6](https://github.com/Genesara/genesara-engine/commit/95d4ad6ba7a2d9f5aa528f5eaa43afa197198a6d))
* **skill-feature:** tick Step 4 ([#68](https://github.com/Genesara/genesara-engine/issues/68)) and advance current step to Step 5 ([#31](https://github.com/Genesara/genesara-engine/issues/31)) ([#76](https://github.com/Genesara/genesara-engine/issues/76)) ([602e18c](https://github.com/Genesara/genesara-engine/commit/602e18c43282ea14a055aa7e33ea6aa08606524c))
* **skill-feature:** tick Step 5 ([#31](https://github.com/Genesara/genesara-engine/issues/31)) and advance current step to Step 6 ([#93](https://github.com/Genesara/genesara-engine/issues/93)) ([b4d8915](https://github.com/Genesara/genesara-engine/commit/b4d89150e88d2bfb425e90c87245e6deb309c657))
* **skill-feature:** tick Step 6 ([#32](https://github.com/Genesara/genesara-engine/issues/32)) and advance current step to Step 7 ([#33](https://github.com/Genesara/genesara-engine/issues/33)) ([#100](https://github.com/Genesara/genesara-engine/issues/100)) ([c071b5e](https://github.com/Genesara/genesara-engine/commit/c071b5efdf872c7f39a112944e92589671e3ae46))
* **skill-feature:** tick Step 7 ([#33](https://github.com/Genesara/genesara-engine/issues/33)) and advance current step to Step 8 ([#34](https://github.com/Genesara/genesara-engine/issues/34)) ([#102](https://github.com/Genesara/genesara-engine/issues/102)) ([3c2cbb0](https://github.com/Genesara/genesara-engine/commit/3c2cbb00490e81d0d5b8d2b07b97cb3aceb4c553))
