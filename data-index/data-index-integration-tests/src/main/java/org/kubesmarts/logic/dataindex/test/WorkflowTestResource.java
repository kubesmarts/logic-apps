package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.Flow;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST endpoints for testing workflows.
 * <p/>
 * Returns CompletableFuture for async workflow execution.
 * JAX-RS/Quarkus handles the async completion automatically.
 */
@Path("/test-workflows")
public class WorkflowTestResource {

    @Inject
    @Identifier("test.SimpleSet")
    Flow simpleSet;

    @POST
    @Path("/simple-set")
    @Produces(MediaType.APPLICATION_JSON)
    public CompletableFuture<Map<String, Object>> executeSimpleSet() {
        Log.info("Executing simple-set workflow (no HTTP)");
        return simpleSet.instance().start()
                .thenApply(model -> model.asMap().orElseThrow());
    }
}
