# ObjectMapper CDI Refactoring

**Date**: 2026-04-17  
**Status**: ✅ **COMPLETE**

---

## 🎯 Objective

Replace manual `ObjectMapper` instantiation with Quarkus-managed CDI beans.

**Problem**: Manual `ObjectMapper` creation bypasses Quarkus configuration and lifecycle management.

**Solution**: Use CDI injection for `ObjectMapper` and provide fallback for non-CDI components.

---

## ❌ Anti-Pattern (Before)

### Manual Instantiation
```java
// ❌ BAD: Manual ObjectMapper creation
public class JsonUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }
}

// ❌ BAD: Manual ObjectMapper in GraphQL scalar
public class JsonNodeScalar {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
}
```

**Problems**:
- Bypasses Quarkus ObjectMapper configuration
- No integration with `application.properties` settings
- Missing custom serializers/deserializers
- Poor testability
- Inconsistent configuration across codebase

---

## ✅ Best Practice (After)

### 1. CDI Beans (Preferred)
```java
// ✅ GOOD: Direct CDI injection
@ApplicationScoped
public class MyService {
    
    @Inject
    ObjectMapper objectMapper;
    
    public void process() {
        JsonNode node = objectMapper.readTree("...");
    }
}
```

### 2. Non-CDI Components (Fallback)
For components that can't use CDI injection (JPA AttributeConverters, GraphQL scalars):

```java
// ✅ GOOD: Use Arc.container() for non-CDI components
import io.quarkus.arc.Arc;

public class JsonBinaryConverter implements AttributeConverter<JsonNode, String> {
    
    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        // Get Quarkus-managed ObjectMapper via CDI container
        ObjectMapper mapper = Arc.container().instance(ObjectMapper.class).get();
        return mapper.writeValueAsString(attribute);
    }
}
```

### 3. Singleton Holder Pattern (Alternative)
For shared access across non-CDI components:

```java
// ✅ GOOD: Singleton that wraps CDI bean
@ApplicationScoped
public class ObjectMapperProducer {
    
    private static ObjectMapperProducer INSTANCE;
    private final ObjectMapper objectMapper;
    
    @Inject
    public ObjectMapperProducer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        INSTANCE = this;
    }
    
    public static ObjectMapper get() {
        return INSTANCE.objectMapper;
    }
}
```

---

## 🔄 Changes Made

### 1. Created ObjectMapperProducer
**File**: `data-index-storage-postgresql/src/main/java/.../json/ObjectMapperProducer.java` (NEW)

```java
@ApplicationScoped
public class ObjectMapperProducer {
    
    @Inject
    public ObjectMapperProducer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        INSTANCE = this;
    }
    
    public static ObjectMapper get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("CDI container not started");
        }
        return INSTANCE.objectMapper;
    }
}
```

**Purpose**: Provides static access to Quarkus-managed ObjectMapper for non-CDI components.

---

### 2. Refactored JsonUtils
**File**: `data-index-storage-postgresql/src/main/java/.../json/JsonUtils.java`

**Before**:
```java
public final class JsonUtils {
    private static final ObjectMapper MAPPER = configure(new ObjectMapper());
    
    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }
    
    public static ObjectMapper configure(ObjectMapper objectMapper) {
        return objectMapper
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule())
                .findAndRegisterModules();
    }
}
```

**After**:
```java
public final class JsonUtils {
    
    public static ObjectMapper getObjectMapper() {
        return ObjectMapperProducer.get();
    }
    
    // Removed configure() method - Quarkus handles configuration
    // Removed static ObjectMapper field
}
```

**Benefits**:
- ✅ Delegates to Quarkus-managed ObjectMapper
- ✅ Removed manual configuration (Quarkus configures via properties)
- ✅ Removed JavaTimeModule registration (Quarkus auto-registers)

---

### 3. Refactored JsonNodeScalar
**File**: `data-index-service/src/main/java/.../graphql/JsonNodeScalar.java`

**Before**:
```java
public class JsonNodeScalar {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    // Used OBJECT_MAPPER directly in methods
}
```

**After**:
```java
public class JsonNodeScalar {
    
    private static ObjectMapper getObjectMapper() {
        return Arc.container().instance(ObjectMapper.class).get();
    }
    
    // parseValue(), parseLiteral(), serialize() now call getObjectMapper()
}
```

**Benefits**:
- ✅ Uses Quarkus-managed ObjectMapper via Arc
- ✅ Works even though JsonNodeScalar is not a CDI bean
- ✅ Consistent configuration with rest of application

---

### 4. JsonBinaryConverter (Already Correct)
**File**: `data-index-storage-postgresql/src/main/java/.../postgresql/JsonBinaryConverter.java`

**Current**:
```java
public class JsonBinaryConverter implements AttributeConverter<JsonNode, String> {
    
    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        // Uses JsonUtils.getObjectMapper() which now delegates to Quarkus
        return JsonUtils.getObjectMapper().writeValueAsString(attribute);
    }
}
```

**Status**: ✅ Already correct - now uses Quarkus ObjectMapper via JsonUtils

---

## 🎯 Component Categories

### CDI Beans (Direct Injection)
Use `@Inject ObjectMapper` directly:
- Event processors
- Storage implementations
- Services
- REST resources

### Non-CDI Components (Arc.container())
Use `Arc.container().instance(ObjectMapper.class).get()`:
- JPA AttributeConverters
- GraphQL scalars
- Hibernate custom types
- Non-CDI utilities

### Utilities (ObjectMapperProducer.get())
Use `ObjectMapperProducer.get()` for convenience:
- Utility classes that can't be CDI beans
- Static helper methods
- Legacy code requiring static access

---

## 📊 Quarkus ObjectMapper Configuration

Quarkus provides a pre-configured ObjectMapper with:

### Auto-Registered Modules
```
✅ JavaTimeModule (JSR-310 date/time)
✅ ParameterNamesModule
✅ Jdk8Module (Optional, etc.)
✅ Custom modules from dependencies
```

### Configuration Properties
```properties
# Jackson configuration (application.properties)
quarkus.jackson.fail-on-unknown-properties=false
quarkus.jackson.write-dates-as-timestamps=false
quarkus.jackson.serialization-inclusion=non-null

# Custom serializers/deserializers auto-discovered
```

### Benefits
- Centralized configuration
- Automatic module registration
- Integration with REST/GraphQL
- Proper lifecycle management
- Better testability

---

## ✅ Verification

### Check for Manual Instantiation
```bash
$ grep -r "new ObjectMapper()" data-index/
# Should only show test code:
data-index-integration-tests/src/test/java/.../EventLogParser.java
```

**Test code is OK**: Tests can have their own ObjectMapper instances.

---

## 🎯 Benefits

### 1. **Consistent Configuration**
- All components use same ObjectMapper configuration
- Changes in `application.properties` apply everywhere
- No configuration drift

### 2. **Quarkus Integration**
- Auto-discovery of custom serializers/deserializers
- Integration with REST/GraphQL endpoints
- Proper lifecycle management

### 3. **Better Testability**
- Can override ObjectMapper in tests via CDI
- Centralized mocking point
- Consistent behavior

### 4. **Maintainability**
- Single source of truth for JSON configuration
- No scattered ObjectMapper instances
- Clear dependency injection

### 5. **Performance**
- Single ObjectMapper instance (thread-safe)
- No redundant module registration
- Quarkus-optimized configuration

---

## 📚 Files Modified

| File | Change | Lines Changed |
|------|--------|---------------|
| `ObjectMapperProducer.java` | NEW | Created CDI singleton holder |
| `JsonUtils.java` | REFACTORED | Removed manual ObjectMapper, removed configure() |
| `JsonNodeScalar.java` | REFACTORED | Uses Arc.container() instead of static field |
| `JsonBinaryConverter.java` | NO CHANGE | Already uses JsonUtils (now delegates to Quarkus) |

**Total**: 3 files modified, 1 file created

---

## 🔍 Usage Examples

### CDI Bean (Preferred)
```java
@ApplicationScoped
public class WorkflowService {
    
    @Inject
    ObjectMapper objectMapper;  // ✅ Direct injection
    
    public WorkflowInstance parse(String json) {
        return objectMapper.readValue(json, WorkflowInstance.class);
    }
}
```

### JPA AttributeConverter
```java
public class JsonConverter implements AttributeConverter<JsonNode, String> {
    
    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        // ✅ Use JsonUtils (delegates to Quarkus)
        return JsonUtils.getObjectMapper().writeValueAsString(attribute);
    }
}
```

### GraphQL Scalar
```java
public class CustomScalar {
    
    private static ObjectMapper getObjectMapper() {
        // ✅ Use Arc.container() for non-CDI components
        return Arc.container().instance(ObjectMapper.class).get();
    }
    
    public Object serialize(Object value) {
        return getObjectMapper().valueToTree(value);
    }
}
```

---

## 📚 Related Documents

- [Quarkus Jackson Guide](https://quarkus.io/guides/rest-json#jackson)
- [Quarkus CDI Reference](https://quarkus.io/guides/cdi-reference)
- [Arc Container API](https://javadoc.io/doc/io.quarkus.arc/arc/latest/io/quarkus/arc/Arc.html)

---

## 🚀 Next Steps

### Optional: Remove JsonNodeScalar
Since we configure JsonNode mapping in `application.properties`:
```properties
quarkus.smallrye-graphql.scalar.com.fasterxml.jackson.databind.JsonNode=Object
```

JsonNodeScalar.java might be redundant. Consider:
1. Test if GraphQL works without JsonNodeScalar
2. If yes, delete the class
3. Rely solely on property-based configuration

---

**Date**: 2026-04-17  
**Author**: Claude Code (Sonnet 4.5)  
**Status**: Complete and tested
