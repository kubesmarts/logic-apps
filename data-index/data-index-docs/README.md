# Data Index Documentation

This module contains the Data Index documentation built with Antora.

## Building the Documentation

The documentation is automatically built when you build the parent project:

```bash
cd data-index
mvn clean package
```

Or build just the docs:

```bash
cd data-index-docs
mvn clean package
```

## Output

**Generated HTML:** `target/generated-docs/`

**Packaged JAR:** `target/data-index-docs-999-SNAPSHOT.jar`
- Resources location: `META-INF/resources/docs/`

## Viewing the Documentation

### Option 1: Included in Data Index Service (Default)

The documentation is automatically included in the `data-index-service` application and served at:

**URL:** `http://localhost:8080/docs`

When you run the data-index-service:
```bash
cd ../data-index-service
mvn quarkus:dev
```

Then open: http://localhost:8080/docs

### Option 2: Standalone

Open the generated HTML directly:

```bash
open target/generated-docs/index.html
```

Or serve with a local web server:

```bash
cd target/generated-docs
python3 -m http.server 8000
# Open http://localhost:8000
```

## Documentation Structure

```
modules/ROOT/
├── nav.adoc                    # Navigation menu
└── pages/
    ├── index.adoc              # Introduction
    ├── getting-started.adoc    # Getting started guide
    ├── api/                    # API reference
    │   ├── graphql-overview.adoc
    │   └── queries.adoc
    ├── architecture/           # Architecture documentation
    │   ├── overview.adoc
    │   ├── postgresql-mode.adoc
    │   └── elasticsearch-mode.adoc
    ├── deployment/             # Deployment guides
    │   ├── overview.adoc
    │   ├── kind-local.adoc
    │   ├── postgresql.adoc
    │   ├── elasticsearch.adoc
    │   └── fluentbit-config.adoc
    ├── developers/             # Developer guides
    │   ├── quarkus-flow-apps.adoc
    │   ├── quarkus-flow-integration.adoc
    │   ├── configuration.adoc
    │   └── troubleshooting.adoc
    └── operations/             # Operations guides
        └── event-reliability.adoc
```

## Editing Documentation

1. Edit AsciiDoc files in `modules/ROOT/pages/`
2. Update navigation in `modules/ROOT/nav.adoc`
3. Rebuild: `mvn clean package`
4. View changes: Open `target/generated-docs/index.html`

## Antora Configuration

- **Playbook:** `antora-playbook.yaml` - Site configuration
- **Component:** `antora.yml` - Component metadata
- **UI Bundle:** Default Antora UI (GitLab)

## Dependencies

- Node.js (installed via frontend-maven-plugin)
- NPM packages:
  - `@antora/cli` - Antora command-line interface
  - `@antora/site-generator` - Site generator

These are automatically installed during the build.

## Production

The documentation is packaged into the `data-index-service` container image and accessible at `/docs` when the service is running.

This allows operations teams to quickly access the manual without leaving the application.
