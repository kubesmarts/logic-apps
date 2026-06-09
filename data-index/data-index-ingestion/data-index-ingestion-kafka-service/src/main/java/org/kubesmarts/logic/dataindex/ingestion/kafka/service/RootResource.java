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
package org.kubesmarts.logic.dataindex.ingestion.kafka.service;

import io.quarkus.qute.Template;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Serves the landing page with dynamic version injection.
 */
@Path("/")
public class RootResource {

    @Inject
    SmallRyeHealthReporter reporter;

    @Inject
    Template index;

    private final String version;
    private String gitCommit;

    public RootResource() {
        Package pkg = getClass().getPackage();
        version = pkg != null && pkg.getImplementationVersion() != null
            ? pkg.getImplementationVersion()
            : "999-SNAPSHOT";

        try (InputStream is = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                gitCommit = props.getProperty("git.commit.id.abbrev", "unknown");
            } else {
                gitCommit = "dev";
            }
        } catch (Exception e) {
            gitCommit = "unknown";
        }
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String root() {
        JsonObject payload = reporter.getHealth().getPayload();
        return index.data("version", version, "payload", payload).render();
    }
}

