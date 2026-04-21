package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.Flow;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST endpoints for testing workflows defined in Java DSL.
 * <p/>
 * Returns CompletableFuture for async workflow execution.
 * JAX-RS/Quarkus handles the async completion automatically.
 */
@Path("/test-workflows")
public class WorkflowTestResource {

    @Inject
    @Identifier("test.SimpleSetJava")
    Flow simpleSetJava;

    @Inject
    @Identifier("test.HelloWorldJava")
    Flow helloWorldJava;

    @POST
    @Path("/simple-set")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public CompletableFuture<Map<String, Object>> executeSimpleSet(Map<String, Object> input) {
        Log.info("Executing simple-set-java workflow with input: " + input);
        return simpleSetJava.instance(input).start()
                .thenApply(model -> model.asMap().orElseThrow());
    }

    @POST
    @Path("/hello-world")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public CompletableFuture<Map<String, Object>> executeHelloWorld(Map<String, Object> input) {
        Log.info("Executing hello-world-java workflow with input: " + input);
        return helloWorldJava.instance(input).start()
                .thenApply(model -> model.asMap().orElseThrow());
    }
}
