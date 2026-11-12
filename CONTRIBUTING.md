# Contributing

## Build and test

Requires Java 17 and Maven 3.9.11+.

```bash
mvn clean install
```

E2E tests (KIND + Helm, per mode): see `data-index/scripts/e2e/`.

## Pull requests

- Target `main`.
- PR titles must follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix(graphql):`, `feat!:` for breaking changes). Checked by CI; PRs are squash-merged, so the title becomes the commit message.
- Add user-facing changes to the `Unreleased` section of [CHANGELOG.md](CHANGELOG.md).

## Releases

See [docs/RELEASE.md](docs/RELEASE.md).
