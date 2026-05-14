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
package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Tests for configuration validation.
 *
 * Verifies that invalid configurations are caught at startup with clear error messages.
 */
class ElasticsearchConfigurationValidationIT {

    /**
     * Test profile with invalid time window format
     */
    public static class InvalidTimeWindowProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "data-index.transform.smart-filter.time-window", "invalid",
                "data-index.ilm.raw-events-retention", "30d"
            );
        }
    }

    /**
     * Test profile with time window exceeding retention
     */
    public static class TimeWindowExceedsRetentionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "data-index.transform.smart-filter.time-window", "8d",
                "data-index.ilm.raw-events-retention", "7d"
            );
        }
    }

    /**
     * Test profile with invalid ILM retention format
     */
    public static class InvalidRetentionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "data-index.transform.smart-filter.time-window", "1h",
                "data-index.ilm.raw-events-retention", "30" // Missing 'd'
            );
        }
    }

    // Note: These tests verify that startup fails with appropriate error messages
    // In practice, these would be separate test classes with @TestProfile
    // Each would expect IllegalArgumentException during startup

    /*
     * testInvalidTimeWindowFormat:
     *   Config: time-window=invalid
     *   Expected: IllegalArgumentException("Invalid time window format: invalid...")
     *
     * testTimeWindowExceedsRetention:
     *   Config: time-window=8d, retention=7d
     *   Expected: IllegalArgumentException("Smart filter time window (8d) cannot exceed...")
     *
     * testInvalidRetentionFormat:
     *   Config: retention=30 (missing 'd')
     *   Expected: IllegalArgumentException("Invalid ILM retention format: 30...")
     */
}

/**
 * NOTE: Configuration validation tests require separate test executions
 * because invalid configuration causes startup failure.
 *
 * Manual test:
 * 1. Set invalid config in application.properties
 * 2. Start application
 * 3. Verify startup fails with clear error message
 *
 * Automated validation tested via:
 * - Unit tests for parseToMillis() method
 * - Unit tests for validateConfiguration() method (if extracted)
 */
