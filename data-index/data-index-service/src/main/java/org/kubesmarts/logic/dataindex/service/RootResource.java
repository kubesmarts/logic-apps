package org.kubesmarts.logic.dataindex.service;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Serves the landing page with dynamic version injection
 */
@Path("/")
public class RootResource {

    private String version;
    private String gitCommit;

    public RootResource() {
        // Load version from package
        Package pkg = getClass().getPackage();
        version = pkg != null && pkg.getImplementationVersion() != null
            ? pkg.getImplementationVersion()
            : "999-SNAPSHOT";

        // Load git.properties if available
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
        return index();
    }

    @GET
    @Path("ui")
    @Produces(MediaType.TEXT_HTML)
    public String ui() {
        return index();
    }

    @GET
    @Path("test")
    @Produces(MediaType.TEXT_PLAIN)
    public String test() {
        return "Test endpoint works!";
    }

    private String index() {
        try (InputStream is = getClass().getResourceAsStream("/templates/index.html")) {
            if (is == null) {
                return "<html><body><h1>Data Index</h1><p>index.html not found at /templates/index.html</p></body></html>";
            }
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Inject version and git commit dynamically
            String versionInfo = version + " (" + gitCommit + ")";
            html = html.replace("{{VERSION}}", versionInfo);

            return html;
        } catch (Exception e) {
            return "<html><body><h1>Error</h1><p>" + e.getMessage() + "</p></body></html>";
        }
    }
}
