# Release Process

## Versioning

[Semantic Versioning](https://semver.org/) `MAJOR.MINOR.PATCH`, shared by all modules and images:

- **MAJOR** - breaking changes: GraphQL API, database/index schema, configuration.
- **MINOR** - new backward-compatible features.
- **PATCH** - backward-compatible bug fixes.

`main` always stays on its `-SNAPSHOT` version. A release never commits version changes: the version is applied only at build time.

## Git tags

`v<MAJOR>.<MINOR>.<PATCH>`, e.g. `v1.0.0`. Tags are created by the release workflow, not by hand.

## Images

Published to quay.io for `linux/amd64` and `linux/arm64`:

| Image | Mode | Tags |
|---|---|---|
| `quay.io/kubesmarts/data-index-service` | MODE 1, MODE 3 (query) | `<version>-postgresql`, `latest-postgresql` |
| `quay.io/kubesmarts/data-index-service` | MODE 2 | `<version>-elasticsearch`, `latest-elasticsearch` |
| `quay.io/kubesmarts/data-index-ingestion` | MODE 3 (ingestion) | `<version>-kafka`, `latest-kafka` |

`latest-*` points to the most recent release. Pin `<version>-*` in production.

## How a release works

Run the *Release* workflow (Actions → Release → Run workflow) with `version` (e.g. `1.0.0`). [`release.yml`](../.github/workflows/release.yml):

1. Tags the current `main` commit `v<version>` (fails if the tag exists).
2. Calls [`publish-images.yml`](../.github/workflows/publish-images.yml), which runs `mvn versions:set -DnewVersion=<version>` in the runner (not committed), builds the JARs and pushes the images.

The source at the tag still reads `-SNAPSHOT` (poms, Antora); the JARs and images carry `<version>`.

## Helm

The Helm chart (`data-index/helm/data-index`) is for local/e2e testing only and is not released. Deployments use the logic-operator.

## Release checklist

1. In a regular PR, move the `Unreleased` entries in [`CHANGELOG.md`](../CHANGELOG.md) to `## [X.Y.Z] - YYYY-MM-DD` and merge it.
2. Check the full test suite (Build + Data Index Integration Tests) is green on `main`.
3. Run the *Release* workflow and check it is green.
4. Verify the images on quay.io:
   ```bash
   skopeo inspect docker://quay.io/kubesmarts/data-index-service:X.Y.Z-postgresql
   skopeo inspect docker://quay.io/kubesmarts/data-index-service:X.Y.Z-elasticsearch
   skopeo inspect docker://quay.io/kubesmarts/data-index-ingestion:X.Y.Z-kafka
   ```
5. Create the GitHub release with the changelog section:
   ```bash
   gh release create vX.Y.Z --title vX.Y.Z --notes-file notes.md
   ```
6. Update deployment docs (`data-index/data-index-docs`) that reference a specific version.

## Rollback

Never delete or move release tags or images.

- Users pin the previous image version (`<previous>-<variant>`) in their deployment.
- Fix forward with a PATCH release.
- `latest-*` moves on the next release.

## Breaking changes

- Only in a MAJOR release.
- List them in `CHANGELOG.md` under **Changed** or **Removed**, prefixed with **BREAKING**, with migration steps.
- Prefer deprecating in a MINOR release (**Deprecated**) before removing.
