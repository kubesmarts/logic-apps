package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.annotations.FlowId;
import io.serverlessworkflow.api.Workflow;
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.actions.SetAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.Map;

/**
 * Simple hello world workflow using Java DSL.
 */
@ApplicationScoped
public class HelloWorldWorkflow {

    @Produces
    @FlowId("test:hello-world-java")
    public Workflow helloWorld() {
        return Workflow.builder()
                .withDocument(doc -> doc
                        .withDsl("1.0.0")
                        .withNamespace("test")
                        .withName("hello-world-java")
                        .withVersion("1.0.0"))
                .withDo(
                        Action.builder()
                                .withSet(SetAction.builder()
                                        .withSet(Map.of(
                                                "message", "Hello, World!",
                                                "author", "Quarkus Flow",
                                                "platform", "Kubernetes"
                                        ))
                                        .build())
                                .build()
                )
                .build();
    }
}
