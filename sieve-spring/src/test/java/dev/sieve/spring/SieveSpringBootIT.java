package dev.sieve.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Real Tomcat, real auto-configuration, real HTTP: priorities decide who is shed. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sieve.initial-limit=1", "sieve.min-limit=1", "sieve.max-limit=1",
                "sieve.windowed=false"})
class SieveSpringBootIT {

    static final CountDownLatch ENTERED = new CountDownLatch(1);
    static final CountDownLatch RELEASE = new CountDownLatch(1);

    @SpringBootApplication
    static class App {
        @RestController
        static class Endpoints {
            @GetMapping("/slow")
            String slow() throws InterruptedException {
                ENTERED.countDown();
                RELEASE.await(10, TimeUnit.SECONDS);
                return "slow";
            }

            @GetMapping("/fast")
            String fast() {
                return "fast";
            }
        }
    }

    @LocalServerPort
    int port;

    private HttpResponse<String> get(HttpClient c, String path, String priority) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Sieve-Priority", priority).build();
        return c.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void shedsLowPriorityButServesCriticalWhenTheLimitIsFull() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // A bulk request takes the only slot and holds it.
        CompletableFuture<HttpResponse<String>> held =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return get(client, "/slow", "bulk");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        ENTERED.await(5, TimeUnit.SECONDS);

        HttpResponse<String> shed = get(client, "/fast", "bulk");
        assertEquals(503, shed.statusCode());
        assertEquals("10", shed.headers().firstValue("Retry-After").orElse(null));
        assertEquals("concurrency_limit", shed.headers().firstValue("X-Sieve-Shed").orElse(null));

        // Critical traffic steals capacity from the lower tiers.
        HttpResponse<String> critical = get(client, "/fast", "critical");
        assertEquals(200, critical.statusCode());
        assertEquals("fast", critical.body());

        RELEASE.countDown();
        assertEquals(200, held.get(10, TimeUnit.SECONDS).statusCode());

        // With the slot free again, bulk is served.
        assertEquals(200, get(client, "/fast", "bulk").statusCode());
    }
}
