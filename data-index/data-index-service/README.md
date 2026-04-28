# Data Index Service

Main Quarkus application that provides a GraphQL API for querying Serverless Workflow execution data.

## Overview

The Data Index service is a read-only query service that:

- Exposes workflow and task execution data via GraphQL API
- Supports multiple storage backends (PostgreSQL, Elasticsearch)
- Provides health checks and metrics endpoints
- Includes built-in documentation

**NOT included in this service:**
- Workflow execution engine (see Quarkus Flow)
- Event collection (see FluentBit configurations)

## Architecture

```
┌──────────────────┐
│ GraphQL API      │  SmallRye GraphQL endpoint at /graphql
├──────────────────┤
│ Domain Model     │  data-index-model (GraphQL types)
├──────────────────┤
│ Storage Layer    │  data-index-storage-common (interfaces)
├──────────────────┤
│ Backend Impl     │  data-index-storage-postgresql (JPA)
└──────────────────┘
```

## Module Structure

This is a **parent aggregator** with three modules:

- **data-index-service-core** - Common code (GraphQL API, REST resources)
- **data-index-service-postgresql** - PostgreSQL backend module (production-ready)
- **data-index-service-elasticsearch** - Elasticsearch backend module (future)

Each backend module includes only its specific dependencies and configuration.

## Building

### Development Mode

**PostgreSQL backend:**

```bash
cd data-index-service-postgresql
mvn quarkus:dev
```

**Elasticsearch backend (future):**

```bash
cd data-index-service-elasticsearch
mvn quarkus:dev
```

**What this does:**
- Loads module-specific `application.properties`
- Starts storage backend container via Dev Services (PostgreSQL 15)
- Runs Flyway migrations automatically (dev mode only)
- Enables live reload on code changes

**Access points:**
- Application: http://localhost:8080
- GraphQL UI: http://localhost:8080/q/graphql-ui
- Documentation: http://localhost:8080/docs
- Health: http://localhost:8080/q/health
- Metrics: http://localhost:8080/q/metrics

### Production Build

**PostgreSQL backend:**

```bash
cd data-index-service-postgresql
mvn clean package -DskipFlyway=true -DskipTests
```

**Result:**
- Optimized Quarkus app at `target/quarkus-app/`
- Container image: `kubesmarts/data-index-service-postgresql:999-SNAPSHOT`
- PostgreSQL dependencies ONLY
- No Flyway (production uses manual schema migration)

**Elasticsearch backend (future):**

```bash
cd data-index-service-elasticsearch
mvn clean package -DskipFlyway=true -DskipTests
```

**Result:**
- Container image: `kubesmarts/data-index-service-elasticsearch:999-SNAPSHOT`

### Container Image

Build container image with Jib:

```bash
cd data-index-service-postgresql
mvn package -DskipFlyway=true \
  -Dquarkus.container-image.build=true \
  -DskipTests
```

**Customization:**
```bash
cd data-index-service-postgresql
mvn package -DskipFlyway=true \
  -Dquarkus.container-image.group=myorg \
  -Dquarkus.container-image.name=data-index-postgresql \
  -Dquarkus.container-image.tag=1.0.0 \
  -Dquarkus.container-image.build=true
```

## Configuration

### Backend Selection

Backend is selected by navigating to the appropriate module:

| Module | Storage | Dependencies | Container Image |
|--------|---------|--------------|----------------|
| `data-index-service-postgresql` | PostgreSQL | JPA, JDBC, Flyway (dev only) | `kubesmarts/data-index-service-postgresql:999-SNAPSHOT` |
| `data-index-service-elasticsearch` | Elasticsearch | Elasticsearch client (future) | `kubesmarts/data-index-service-elasticsearch:999-SNAPSHOT` |

### Configuration Files

**Common configuration** (`data-index-service-core/src/main/resources/application.properties`):
- GraphQL endpoint: `/graphql`
- HTTP compression
- Health checks: `/q/health`
- Metrics: `/q/metrics`

**PostgreSQL configuration** (`data-index-service-postgresql/src/main/resources/application.properties`):
- Dev Services: auto-starts PostgreSQL 15
- Database: `dataindex`
- Username: `dataindex`
- Password: `dataindex123`
- Flyway: enabled in `%dev` mode only, disabled in `%prod`

**Elasticsearch configuration** (`data-index-service-elasticsearch/src/main/resources/application.properties`):
- Placeholder for future implementation

### Production Environment Variables

Override database connection:

```bash
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://postgresql:5432/dataindex
QUARKUS_DATASOURCE_USERNAME=dataindex
QUARKUS_DATASOURCE_PASSWORD=***
```

See: [Configuration Reference](../data-index-docs/modules/ROOT/pages/developers/configuration.adoc)

## Development

### Project Structure

```
data-index-service/                      # Parent aggregator POM
├── data-index-service-core/             # Common code
│   ├── src/main/java/.../service/
│   │   └── RootResource.java            # Landing page (/, /docs redirect)
│   ├── src/main/java/.../graphql/
│   │   ├── WorkflowInstanceGraphQLApi.java  # GraphQL queries
│   │   └── filter/                      # GraphQL filter converters
│   └── src/main/resources/
│       ├── application.properties       # Common config
│       └── templates/
│           └── index.html               # Landing page template
├── data-index-service-postgresql/       # PostgreSQL backend
│   ├── pom.xml                          # PostgreSQL dependencies (JPA, JDBC, Flyway)
│   └── src/main/resources/
│       └── application.properties       # PostgreSQL-specific config
└── data-index-service-elasticsearch/    # Elasticsearch backend (future)
    ├── pom.xml                          # Elasticsearch dependencies
    └── src/main/resources/
        └── application.properties       # Elasticsearch-specific config
```

### Adding a GraphQL Query

1. Add method to `WorkflowInstanceGraphQLApi.java`:

```java
@Query
public List<WorkflowInstance> getWorkflowsByStatus(
    @DefaultValue("RUNNING") WorkflowInstanceStatus status
) {
    AttributeFilter filter = new AttributeFilter("status", EQUAL, status);
    return workflowInstanceStorage.query()
        .filter(List.of(filter))
        .execute();
}
```

2. Test in GraphQL UI:

```graphql
{
  getWorkflowsByStatus(status: COMPLETED) {
    id
    name
    status
  }
}
```

3. Add integration test in `WorkflowInstanceGraphQLApiTest.java`

### Testing

Run integration tests:

```bash
# PostgreSQL backend tests
cd data-index-service-postgresql
mvn test

# All modules
cd ..
mvn verify
```

**Important:** Integration tests use `@QuarkusTest` with real PostgreSQL via Dev Services.

**Test setup:**
- Tests are in `data-index-service-core/src/test/java/`
- Executed by backend modules via dependency
- PostgreSQL container auto-starts for tests
- Flyway migrations run automatically in test mode

## Exposed Endpoints

### Application Endpoints

- `GET /` - Landing page with version info and quick links
- `GET /docs` - Redirect to documentation (→ `/docs/`)
- Static resources at `/docs/` - Antora-generated documentation (from data-index-docs module)

### GraphQL API

- `POST /graphql` - GraphQL endpoint
- `GET /q/graphql-ui` - GraphiQL interactive UI
- `GET /graphql/schema.graphql` - GraphQL schema (SDL)

### Operations Endpoints

- `GET /q/health` - Health checks (liveness and readiness)
- `GET /q/health/live` - Liveness probe
- `GET /q/health/ready` - Readiness probe
- `GET /q/metrics` - Prometheus metrics

**No other endpoints are exposed.** Removed in production:
- `/ui` (duplicate of `/`)
- `/test` (debug endpoint)

## Dependencies

### Required at Runtime

**Storage backend** (one of):
- `data-index-storage-postgresql` - PostgreSQL implementation (production-ready)
- `data-index-storage-elasticsearch` - Elasticsearch implementation (future)

**Domain model:**
- `data-index-model` - GraphQL types, domain interfaces

**Documentation:**
- `data-index-docs` - Served at `/docs/`

### Optional (Development Only)

**Flyway migrations:**
- `data-index-storage-migrations` - SQL migration scripts
- Only included when `-DskipFlyway` is NOT set
- Production builds exclude this (`-DskipFlyway=true`)

### External Dependencies

**Minimal Kogito dependency:**
```xml
<dependency>
  <groupId>org.kie.kogito</groupId>
  <artifactId>persistence-commons-api</artifactId>
  <version>999-SNAPSHOT</version>
</dependency>
```

**Used for:**
- `org.kie.kogito.persistence.api.Storage` interface
- `org.kie.kogito.persistence.api.query.*` (AttributeFilter, AttributeSort)

## Deployment

### Local Development

See: [Local Development Guide](../data-index-docs/modules/ROOT/pages/deployment/kind-local.adoc)

### Kubernetes

See: [PostgreSQL Deployment](../data-index-docs/modules/ROOT/pages/deployment/postgresql.adoc)

### KIND (Kubernetes in Docker)

```bash
# From data-index/scripts/kind/
./setup-cluster.sh
MODE=postgresql ./install-dependencies.sh
./deploy-data-index.sh postgresql
```

## Troubleshooting

### Build Issues

**"Cannot resolve dependencies"**
- Ensure parent `data-index/pom.xml` is installed: `mvn install -pl . -am`
- Check Maven profile is active: `mvn help:active-profiles`

**"Flyway not found in production build"**
- Expected behavior when using `-DskipFlyway=true`
- Flyway is dev/test only, excluded in production

### Runtime Issues

**"No datasource configured"**
- Check `QUARKUS_DATASOURCE_JDBC_URL` environment variable is set
- Verify backend profile matches dependencies (postgresql vs elasticsearch)

**"GraphQL schema error"**
- Rebuild project: `mvn clean package`
- Check `data-index-model` classes are indexed for reflection

**"Documentation not found (404)"**
- Verify `data-index-docs` module is built: `mvn package -pl data-index-docs`
- Check documentation jar is in `lib/main/` directory

## Related Documentation

- [Architecture Overview](../data-index-docs/modules/ROOT/pages/architecture/overview.adoc)
- [Configuration Reference](../data-index-docs/modules/ROOT/pages/developers/configuration.adoc)
- [GraphQL API Guide](../data-index-docs/modules/ROOT/pages/api/graphql.adoc)
- [Developer Guide](../data-index-docs/modules/ROOT/pages/developers/contributing.adoc)

## Contributing

See: [Contributing Guide](../data-index-docs/modules/ROOT/pages/developers/contributing.adoc)

**Quick checklist:**
- Write integration tests (`@QuarkusTest`)
- Update documentation in `data-index-docs/`
- Follow existing code patterns (MapStruct, JAX-RS, SmallRye GraphQL)
- Don't add unnecessary dependencies
- Don't add comments explaining WHAT (only WHY)
