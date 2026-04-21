# Quarkus Flow Issue: Workflow YAML files not packaged in JAR, hardcoded absolute paths fail in containers

## Summary

Quarkus Flow does not package workflow YAML files from `src/main/flow/` into the application JAR at build time. Instead, the build-time code generation stores absolute source file paths, which fail at runtime when the application runs in a container.

## Environment

- **Quarkus**: 3.34.3
- **Quarkus Flow**: 1.0.0-SNAPSHOT  
- **Serverless Workflow API**: 7.17.1.Final
- **Build**: Maven 3.9.14, Java 17
- **Runtime**: Docker container (UBI9 OpenJDK 17)

## Problem

During Quarkus build time, workflow definitions in `src/main/flow/` are discovered and registered. However:

1. The workflow YAML files are **NOT** copied into the JAR
2. The generated code stores **absolute source paths** like `/Users/ricferna/dev/github/.../src/main/flow/simple-set.sw.yaml`
3. At runtime (especially in containers), these absolute paths don't exist
4. Workflow initialization fails with `java.nio.file.NoSuchFileException`

## Steps to Reproduce

### 1. Create a Quarkus Flow application

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.quarkiverse.flow</groupId>
  <artifactId>quarkus-flow</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Add workflow YAML file

```yaml
# src/main/flow/simple-set.sw.yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: simple-set
  version: '1.0.0'
do:
  - setMessage:
      set:
        greeting: Hello from Quarkus Flow!
        timestamp: '${ now() }'
```

### 3. Build and package

```bash
mvn clean package
```

### 4. Check JAR contents

```bash
jar tf target/quarkus-app/quarkus-run.jar | grep -E "(flow|\.sw\.yaml)"
# NO WORKFLOW FILES FOUND
```

### 5. Run in Docker container

```dockerfile
FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.23
COPY target/quarkus-app/ /deployments/
EXPOSE 8080
USER 185
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
```

```bash
docker build -t workflow-test .
docker run -p 8080:8080 workflow-test
```

## Error at Runtime

```
Caused by: jakarta.enterprise.inject.CreationException: Error creating synthetic bean: 
java.io.UncheckedIOException: Failed to create WorkflowDefinition for workflow at 
/Users/ricferna/dev/github/project/src/main/flow/simple-set.sw.yaml

Caused by: java.nio.file.NoSuchFileException: 
/Users/ricferna/dev/github/project/src/main/flow/simple-set.sw.yaml
	at java.base/sun.nio.fs.UnixException.translateToIOException(UnixException.java:92)
	at io.serverlessworkflow.api.WorkflowReader.readWorkflow(WorkflowReader.java:150)
	at io.quarkiverse.flow.recorders.WorkflowDefinitionRecorder.lambda$workflowDefinitionFromFileSupplier$1(WorkflowDefinitionRecorder.java:51)
```

## Expected Behavior

1. **Build time**: Workflow YAML files in `src/main/flow/` should be packaged into the JAR
   - Suggested location: `META-INF/workflows/` or similar classpath location
   
2. **Runtime**: Workflow definitions should be loaded from classpath resources, not absolute file paths
   - Use `ClassLoader.getResource()` or `Class.getResourceAsStream()`
   - Paths should be relative to classpath root

3. **Generated code**: Should reference classpath resources:
   ```java
   // Instead of this (current):
   WorkflowReader.readWorkflow("/Users/ricferna/.../src/main/flow/simple-set.sw.yaml")
   
   // Should be:
   WorkflowReader.readWorkflow(
       getClass().getClassLoader().getResource("META-INF/workflows/simple-set.sw.yaml")
   )
   ```

## Workaround

Use Java DSL to define workflows programmatically instead of YAML files:

```java
@ApplicationScoped
public class SimpleSetWorkflow {
    @Produces
    @FlowId("test:simple-set")
    public Workflow simpleSet() {
        return Workflow.builder()
                .withDocument(doc -> doc
                        .withDsl("1.0.0")
                        .withNamespace("test")
                        .withName("simple-set")
                        .withVersion("1.0.0"))
                .withDo(
                        Action.builder()
                                .withSet(SetAction.builder()
                                        .withSet(Map.of(
                                                "greeting", "Hello from Java DSL!",
                                                "timestamp", OffsetDateTime.now().toString()
                                        ))
                                        .build())
                                .build()
                )
                .build();
    }
}
```

This workaround avoids file packaging issues entirely.

## Impact

- **High**: This breaks containerized deployments (Kubernetes, Docker)
- **Blocker**: Cannot use YAML workflow definitions in production containers
- **Workaround exists**: Java DSL works, but less declarative than YAML

## Suggested Fix

Update `io.quarkiverse.flow.recorders.WorkflowDefinitionRecorder` to:

1. Copy workflow YAML files to classpath during build (e.g., `target/classes/META-INF/workflows/`)
2. Store classpath-relative paths instead of absolute source paths
3. Load workflows from classpath resources at runtime

## Additional Context

- This issue only affects YAML workflow definitions in `src/main/flow/`
- Java DSL workflows (CDI `@Produces` methods) work correctly
- The problem is in build-time code generation, not runtime workflow execution

## Related Files

- `io.quarkiverse.flow.recorders.WorkflowDefinitionRecorder` - Build-time recorder
- `io.serverlessworkflow.api.WorkflowReader` - Runtime workflow loader

---

**Environment Details:**
- Project: KubeSmarts Logic Apps Data Index
- Use case: Kubernetes-native workflow orchestration
- Deployment target: OpenShift / Kubernetes clusters
