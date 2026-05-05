/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BucketStringDeserializer.
 *
 * <p>Tests deserialization of Elasticsearch transform bucket format:
 * <pre>
 * {
 *   "name": {"simple-set": 1}    ← Bucket format (key: count)
 * }
 * </pre>
 *
 * <p>The deserializer must handle:
 * <ul>
 *   <li>Bucket format: Extract first key from {"value": count} object
 *   <li>Plain string: Pass through as-is
 *   <li>Null values: Return null
 *   <li>Empty buckets: Return null
 *   <li>Multiple bucket values: Extract first alphabetically
 * </ul>
 */
class BucketStringDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // Test POJO with bucket deserializer
    static class TestModel {
        @JsonDeserialize(using = BucketStringDeserializer.class)
        private String name;

        @JsonDeserialize(using = BucketStringDeserializer.class)
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    @Test
    void testDeserializeBucketFormat() throws JsonProcessingException {
        // Given: JSON with bucket format (typical Elasticsearch transform output)
        String json = """
            {
              "name": {"simple-set": 1},
              "description": {"workflow description": 2}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Extracts first key from bucket
        assertThat(result.getName()).isEqualTo("simple-set");
        assertThat(result.getDescription()).isEqualTo("workflow description");
    }

    @Test
    void testDeserializePlainString() throws JsonProcessingException {
        // Given: JSON with plain string value
        String json = """
            {
              "name": "greeting",
              "description": "Simple greeting workflow"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Pass through as-is
        assertThat(result.getName()).isEqualTo("greeting");
        assertThat(result.getDescription()).isEqualTo("Simple greeting workflow");
    }

    @Test
    void testDeserializeNullValue() throws JsonProcessingException {
        // Given: JSON with null value
        String json = """
            {
              "name": null,
              "description": "test"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Returns null
        assertThat(result.getName()).isNull();
        assertThat(result.getDescription()).isEqualTo("test");
    }

    @Test
    void testDeserializeMissingField() throws JsonProcessingException {
        // Given: JSON with missing field
        String json = """
            {
              "name": "greeting"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Missing field is null
        assertThat(result.getName()).isEqualTo("greeting");
        assertThat(result.getDescription()).isNull();
    }

    @Test
    void testDeserializeEmptyBucket() throws JsonProcessingException {
        // Given: JSON with empty bucket object
        String json = """
            {
              "name": {},
              "description": "test"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Empty bucket returns null
        assertThat(result.getName()).isNull();
        assertThat(result.getDescription()).isEqualTo("test");
    }

    @Test
    void testDeserializeMultipleBucketValues() throws JsonProcessingException {
        // Given: JSON with multiple bucket values (rare, but possible during aggregation)
        String json = """
            {
              "name": {
                "workflow-v2": 2,
                "workflow-v1": 1,
                "workflow-v3": 1
              }
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Returns first value (iteration order may vary)
        assertThat(result.getName()).isNotNull();
        assertThat(result.getName()).isIn("workflow-v1", "workflow-v2", "workflow-v3");
    }

    @Test
    void testDeserializeBucketWithNumericCount() throws JsonProcessingException {
        // Given: JSON with bucket having numeric count value
        String json = """
            {
              "name": {"simple-set": 5}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Extracts key regardless of count value
        assertThat(result.getName()).isEqualTo("simple-set");
    }

    @Test
    void testDeserializeBucketWithZeroCount() throws JsonProcessingException {
        // Given: JSON with bucket having zero count
        String json = """
            {
              "name": {"simple-set": 0}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Still extracts key (count doesn't matter)
        assertThat(result.getName()).isEqualTo("simple-set");
    }

    @Test
    void testDeserializeSpecialCharacters() throws JsonProcessingException {
        // Given: JSON with special characters in bucket key
        String json = """
            {
              "name": {"workflow:with/special\\\\chars": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Preserves special characters
        assertThat(result.getName()).isEqualTo("workflow:with/special\\chars");
    }

    @Test
    void testDeserializeWhitespace() throws JsonProcessingException {
        // Given: JSON with whitespace in bucket key
        String json = """
            {
              "name": {"  workflow with spaces  ": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Preserves whitespace
        assertThat(result.getName()).isEqualTo("  workflow with spaces  ");
    }

    @Test
    void testDeserializeUnicodeCharacters() throws JsonProcessingException {
        // Given: JSON with Unicode characters
        String json = """
            {
              "name": {"工作流程": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Preserves Unicode
        assertThat(result.getName()).isEqualTo("工作流程");
    }
}
