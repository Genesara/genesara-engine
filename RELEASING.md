# Releasing

This repository uses [release-please](https://github.com/googleapis/release-please)
to cut releases automatically from Conventional Commits on `main`. There is no
manual `git tag && git push` step.

## Mechanism

1. Every push to `main` triggers `.github/workflows/release-please.yml`.
2. The action maintains a long-lived "release PR" (`chore: release X.Y.Z`)
   whose body lists every `feat:` / `fix:` / `perf:` commit since the last tag
   and bumps the version in `.release-please-manifest.json`.
3. Merging the release PR causes release-please to:
   - tag the merge commit `v<X.Y.Z>` (configured via `include-v-in-tag: true`),
   - create the corresponding GitHub Release with auto-generated notes.
4. The release PR merge also triggers the `publish-image` and `publish-schema`
   jobs in the same workflow file, gated on `release_created == 'true'`.

## What gets published per release

| Artifact | Where | URL contract |
| --- | --- | --- |
| OCI image | GHCR | `ghcr.io/genesara/genesara-engine:<version>` and `:latest` |
| MCP tool schema | Release asset | `https://github.com/Genesara/genesara-engine/releases/latest/download/schema.json` |

The schema asset is consumed by `docs.genesara.com`
(`Genesara/genesara-docs:scripts/build-tools-json.ts`). Its shape is a
**cross-repo contract** — see `api/src/test/.../mcp/schema/McpSchemaExporter.kt`
KDoc and the `McpSchemaExporterTest` guards.

## To cut a release

1. Ensure your commits since the last tag follow Conventional Commits
   (`feat:`, `fix:`, `perf:`, etc. — see `.github/workflows/semantic-pr.yml`).
2. Find the open release-please PR titled `chore: release X.Y.Z` on GitHub.
3. Review the changelog block, edit if you want to amend, then **squash-merge**.
4. Wait ~2–5 minutes for the workflow to:
   - tag + create the release,
   - build & push the OCI image,
   - run `./gradlew :api:exportMcpSchema` and upload `schema.json`.
5. Confirm:

   ```sh
   curl -sL https://github.com/Genesara/genesara-engine/releases/latest/download/schema.json \
     | jq '.version, (.tools | length)'
   ```

## Updating the MCP schema locally

The exporter doesn't need a database or Redis — it walks the `@Tool`-annotated
classes by reflection and serialises Spring AI's resolved `ToolDefinition`s.

```sh
./gradlew :api:exportMcpSchema
jq '.tools | map(.name)' schema/schema.json
```

The output (`schema/schema.json`) is git-ignored; it is a build artifact, not
source. The authoritative description of every tool lives on its `@Tool` /
`@ToolParam` annotations in `:api`.

## Schema-contract rules

`schema.json` is the wire contract with the docs repository. **Do not** silently:

- rename the file (`releases/latest/download/schema.json` is hard-coded by docs),
- add top-level fields beyond `version`, `generatedAt`, `tools`,
- add per-tool fields beyond `name`, `description`, `inputSchema`, `errors`,
- change `inputSchema` from JSON Schema Draft 2020-12.

Any of these is a breaking change and needs a coordinated update in
`Genesara/genesara-docs` first.
