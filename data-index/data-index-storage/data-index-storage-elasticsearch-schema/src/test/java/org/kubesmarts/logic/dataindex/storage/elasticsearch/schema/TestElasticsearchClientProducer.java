/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kubesmarts.logic.dataindex.storage.elasticsearch.schema;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TestElasticsearchClientProducer {

    @ConfigProperty(name = "quarkus.elasticsearch.hosts", defaultValue = "localhost:9200")
    String elasticsearchHosts;

    @Produces
    @DefaultBean
    public ElasticsearchClient createElasticsearchClient() {
        String[] hostPort = elasticsearchHosts.split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 9200;

        RestClient restClient = RestClient
                .builder(new HttpHost(host, port, "http"))
                .setDefaultHeaders(new BasicHeader[]{
                        new BasicHeader("Content-Type", "application/json"),
                        new BasicHeader("Accept", "application/json")
                })
                .build();

        RestClientTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );

        return new ElasticsearchClient(transport);
    }

    public void dispose(@Disposes ElasticsearchClient client) throws Exception {
        if (client != null && client._transport() instanceof RestClientTransport transport) {
            transport.close();
        }
    }
}
