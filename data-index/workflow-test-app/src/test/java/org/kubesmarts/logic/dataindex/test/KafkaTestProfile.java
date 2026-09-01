package org.kubesmarts.logic.dataindex.test;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Test profile that activates Kafka messaging.
 */
public class KafkaTestProfile implements QuarkusTestProfile {

    @Override
    public String getConfigProfile() {
        return "kafka";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.flow.structured-logging.enabled", "false",
            "quarkus.flow.messaging.lifecycle-enabled", "true"
        );
    }
}
