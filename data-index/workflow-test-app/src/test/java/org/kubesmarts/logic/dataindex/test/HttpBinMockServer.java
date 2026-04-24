package org.kubesmarts.logic.dataindex.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * WireMock test resource that mocks httpbin.org endpoints for workflow testing.
 *
 * Configures stubs for:
 * - GET /json - returns sample slideshow JSON
 * - GET /status/500 - returns HTTP 500 error
 */
public class HttpBinMockServer implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMock;

    // Use fixed port 28080 for WireMock (workflows hardcode this URL)
    private static final int WIREMOCK_PORT = 28080;

    @Override
    public Map<String, String> start() {
        wireMock = new WireMockServer(options().port(WIREMOCK_PORT));
        wireMock.start();

        configureFor("localhost", WIREMOCK_PORT);

        // Stub GET /json to return the expected httpbin.org response
        stubFor(get(urlPathEqualTo("/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "slideshow": {
                                    "title": "Sample Slide Show",
                                    "author": "Yours Truly",
                                    "date": "date of publication",
                                    "slides": [
                                      {
                                        "type": "all",
                                        "title": "Wake up to WonderWidgets!"
                                      },
                                      {
                                        "type": "all",
                                        "title": "Overview",
                                        "items": [
                                          "Why <em>WonderWidgets</em> are great",
                                          "Who <em>buys</em> WonderWidgets"
                                        ]
                                      }
                                    ]
                                  }
                                }
                                """)));

        // Stub GET /status/500 to return HTTP 500 error
        stubFor(get(urlPathEqualTo("/status/500"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal Server Error\"}")));

        // No config needed - workflows use hardcoded localhost:28080
        return Map.of();
    }

    @Override
    public void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }
}
