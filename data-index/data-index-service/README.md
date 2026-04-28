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

## Building

### Development Mode

Start with PostgreSQL backend (default):

```bash
mvn quarkus:dev -Dquarkus.profile=postgresql
```

**What this does:**
- Activates Maven `postgresql` profile → includes PostgreSQL dependencies
- Loads `application-postgresql.properties` configuration
- Starts PostgreSQL 15 container via Dev Services
- Runs Flyway migrations automatically
- Enables live reload on code changes

**Access points:**
- Application: http://localhost:8080
- GraphQL UI: http://localhost:8080/q/graphql-ui
- Documentation: http://localhost:8080/docs
- Health: http://localhost:8080/q/health
- Metrics: http://localhost:8080/q/metrics

### Production Build

Build with PostgreSQL backend (excludes Flyway):

```bash
mvn clean package -Dquarkus.profile=postgresql -DskipFlyway=true -DskipTests
```

**Result:**
- Optimized Quarkus app at `target/quarkus-app/`
- Container image: `kubesmarts/data-index-service:999-SNAPSHOT`
- PostgreSQL storage dependencies ONLY
- No Flyway (production uses external schema management)

Build with Elasticsearch backend (future):

```bash
mvn clean package -Dquarkus.profile=elasticsearch -DskipFlyway=true -DskipTests
```

### Container Image

Build container image with Jib:

```bash
mvn package -Dquarkus.profile=postgresql \
  -DskipFlyway=true \
  -Dquarkus.container-image.build=true
```

**Customization:**
```bash
# Custom image name
mvn package -Dquarkus.container-image.group=myorg \
  -Dquarkus.container-image.name=data-index \
  -Dquarkus.container-image.tag=1.0.0 \
  -Dquarkus.container-image.build=true
```

## Configuration

### Backend Selection

Backend is selected via `-Dquarkus.profile=<backend>`:

| Profile | Maven Profile Activated | Config File Loaded | Dependencies Included |
|---------|------------------------|-------------------|---------------------|
| `postgresql` | `postgresql` | `application-postgresql.properties` | PostgreSQL, JPA, Flyway (dev only) |
| `elasticsearch` | `elasticsearch` | `application-elasticsearch.properties` | Elasticsearch (future) |

### Configuration Files

**Common configuration** (`application.properties`):
- GraphQL endpoint: `/graphql`
- HTTP compression
- Health checks: `/q/health`
- Metrics: `/q/metrics`

**PostgreSQL configuration** (`application-postgresql.properties`):
- Dev Services: auto-starts PostgreSQL 15
- Database: `dataindex`
- Username: `dataindex`
- Password: `dataindex123`
- Flyway: enabled in `%dev` mode only

**Production environment variables:**
```bash
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://postgresql:5432/dataindex
QUARKUS_DATASOURCE_USERNAME=dataindex
QUARKUS_DATASOURCE_PASSWORD=***
```

See: [Configuration Reference](../data-index-docs/modules/ROOT/pages/developers/configuration.adoc)

## Development

### Project Structure

```
data-index-service/
├── src/main/java/.../service/
│   └── RootResource.java           # Landing page (/, /docs redirect)
├── src/main/java/.../graphql/
│   ├── WorkflowInstanceGraphQLApi.java  # GraphQL queries
│   └── filter/                     # GraphQL filter converters
├── src/main/resources/
│   ├── application.properties      # Common config
│   ├── application-postgresql.properties
│   ├── application-elasticsearch.properties
│   └── templates/
│       └── index.html              # Landing page template
└── src/test/java/
    └── .../graphql/
        └── WorkflowInstanceGraphQLApiTest.java
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
# PostgreSQL backend
mvn test -Dquarkus.profile=postgresql

# All tests
mvn verify
```

**Important:** Integration tests use `@QuarkusTest` with real PostgreSQL via Dev Services.

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
