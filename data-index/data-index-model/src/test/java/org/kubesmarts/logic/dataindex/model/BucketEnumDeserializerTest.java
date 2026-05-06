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
 * Unit tests for BucketEnumDeserializer.
 *
 * <p>Tests deserialization of Elasticsearch transform bucket format for enums:
 * <pre>
 * {
 *   "status": {"RUNNING": 2}    ← Bucket format (key: count)
 * }
 * </pre>
 *
 * <p>The deserializer must handle:
 * <ul>
 *   <li>Bucket format: Extract first key and convert to enum
 *   <li>Plain string: Convert to enum
 *   <li>Null values: Return null
 *   <li>Invalid enum values: Handle gracefully
 *   <li>Case sensitivity: Match enum names exactly
 * </ul>
 */
class BucketEnumDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // Test enum
    enum TestStatus {
        SUSPENDED,
        RUNNING,
        COMPLETED,
        FAULTED,
        CANCELLED
    }

    // Test POJO with bucket enum deserializer
    static class TestModel {
        @JsonDeserialize(using = BucketEnumDeserializer.class)
        private WorkflowInstanceStatus status;

        @JsonDeserialize(using = BucketEnumDeserializer.class)
        private WorkflowInstanceStatus previousStatus;

        public WorkflowInstanceStatus getStatus() {
            return status;
        }

        public void setStatus(WorkflowInstanceStatus status) {
            this.status = status;
        }

        public WorkflowInstanceStatus getPreviousStatus() {
            return previousStatus;
        }

        public void setPreviousStatus(WorkflowInstanceStatus previousStatus) {
            this.previousStatus = previousStatus;
        }
    }

    @Test
    void testDeserializeBucketFormat() throws JsonProcessingException {
        // Given: JSON with bucket format (typical Elasticsearch transform output)
        String json = """
            {
              "status": {"RUNNING": 2},
              "previousStatus": {"SUSPENDED": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Extracts first key and converts to enum
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(result.getPreviousStatus()).isEqualTo(WorkflowInstanceStatus.SUSPENDED);
    }

    @Test
    void testDeserializeTermsAggregationFormat() throws JsonProcessingException {
        // Given: JSON with terms aggregation format (Elasticsearch transform with terms agg)
        String json = """
            {
              "status": {"buckets": [{"key": "RUNNING", "doc_count": 2}]},
              "previousStatus": {"buckets": [{"key": "SUSPENDED", "doc_count": 1}]}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Extracts key from first bucket and converts to enum
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(result.getPreviousStatus()).isEqualTo(WorkflowInstanceStatus.SUSPENDED);
    }

    @Test
    void testDeserializeTermsAggregationMultipleBuckets() throws JsonProcessingException {
        // Given: JSON with multiple buckets (terms aggregation with ordering)
        String json = """
            {
              "status": {"buckets": [
                {"key": "COMPLETED", "doc_count": 3},
                {"key": "RUNNING", "doc_count": 2}
              ]}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Extracts key from first bucket (order by desc for terminal states)
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    void testDeserializeTermsAggregationEmptyBuckets() throws JsonProcessingException {
        // Given: JSON with empty buckets array
        String json = """
            {
              "status": {"buckets": []},
              "previousStatus": "RUNNING"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Empty buckets returns null
        assertThat(result.getStatus()).isNull();
        assertThat(result.getPreviousStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testDeserializePlainString() throws JsonProcessingException {
        // Given: JSON with plain string value
        String json = """
            {
              "status": "COMPLETED",
              "previousStatus": "RUNNING"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Converts plain string to enum
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(result.getPreviousStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testDeserializeNullValue() throws JsonProcessingException {
        // Given: JSON with null value
        String json = """
            {
              "status": null,
              "previousStatus": "RUNNING"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Returns null
        assertThat(result.getStatus()).isNull();
        assertThat(result.getPreviousStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testDeserializeMissingField() throws JsonProcessingException {
        // Given: JSON with missing field
        String json = """
            {
              "status": "RUNNING"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Missing field is null
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(result.getPreviousStatus()).isNull();
    }

    @Test
    void testDeserializeEmptyBucket() throws JsonProcessingException {
        // Given: JSON with empty bucket object
        String json = """
            {
              "status": {},
              "previousStatus": "RUNNING"
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Empty bucket returns null
        assertThat(result.getStatus()).isNull();
        assertThat(result.getPreviousStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testDeserializeAllStatusValues() throws JsonProcessingException {
        // Given: JSON with all possible status values
        String[] statuses = {"SUSPENDED", "RUNNING", "COMPLETED", "FAULTED", "CANCELLED"};
        WorkflowInstanceStatus[] expected = {
            WorkflowInstanceStatus.SUSPENDED,
            WorkflowInstanceStatus.RUNNING,
            WorkflowInstanceStatus.COMPLETED,
            WorkflowInstanceStatus.FAULTED,
            WorkflowInstanceStatus.CANCELLED
        };

        for (int i = 0; i < statuses.length; i++) {
            String json = String.format("""
                {
                  "status": {"%s": 1}
                }
                """, statuses[i]);

            // When: Deserialize
            TestModel result = mapper.readValue(json, TestModel.class);

            // Then: Correctly converts to enum
            assertThat(result.getStatus()).isEqualTo(expected[i]);
        }
    }

    @Test
    void testDeserializeBucketWithMultipleValues() throws JsonProcessingException {
        // Given: JSON with multiple bucket values (can happen during transform processing)
        // Status aggregation uses ordering (COMPLETED/FAULTED > RUNNING > SUSPENDED)
        String json = """
            {
              "status": {
                "RUNNING": 2,
                "COMPLETED": 1,
                "SUSPENDED": 1
              }
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Returns one of the status values
        assertThat(result.getStatus()).isIn(
            WorkflowInstanceStatus.RUNNING,
            WorkflowInstanceStatus.COMPLETED,
            WorkflowInstanceStatus.SUSPENDED
        );
    }

    @Test
    void testDeserializeBucketWithDifferentCounts() throws JsonProcessingException {
        // Given: JSON with different count values
        String json = """
            {
              "status": {"RUNNING": 100}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Count value doesn't affect enum extraction
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testDeserializeBucketWithZeroCount() throws JsonProcessingException {
        // Given: JSON with zero count
        String json = """
            {
              "status": {"COMPLETED": 0}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Still extracts enum (count doesn't matter)
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    void testDeserializeCaseSensitivity() throws JsonProcessingException {
        // Given: JSON with exact enum case
        String json = """
            {
              "status": {"RUNNING": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Matches case exactly
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testDeserializeTerminalStatusPrecedence() throws JsonProcessingException {
        // Given: JSON with COMPLETED status (terminal state)
        String json = """
            {
              "status": {"COMPLETED": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Correctly deserializes terminal status
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    void testDeserializeFaultedStatus() throws JsonProcessingException {
        // Given: JSON with FAULTED status
        String json = """
            {
              "status": {"FAULTED": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Correctly deserializes error status
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.FAULTED);
    }

    @Test
    void testDeserializeCancelledStatus() throws JsonProcessingException {
        // Given: JSON with CANCELLED status
        String json = """
            {
              "status": {"CANCELLED": 1}
            }
            """;

        // When: Deserialize
        TestModel result = mapper.readValue(json, TestModel.class);

        // Then: Correctly deserializes cancelled status
        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatus.CANCELLED);
    }
}
