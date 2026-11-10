package com.example.ledger.v1.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import com.example.ledger.LedgerApplication;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LEDGER_MYSQL_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "ledger.outbox.enabled=false"})
class AccountIntegrationTest {
    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void concurrentRetriesReturnTheSameAccount() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = open(key, """
                {"currency":"EUR","overdraftLimit":"25.50"}
                """);
        try (var other = new SpringApplicationBuilder(LedgerApplication.class).run("--server.port=0", "--spring.rabbitmq.listener.simple.auto-startup=false", "--ledger.outbox.enabled=false")) {
            int otherPort = ((WebServerApplicationContext) other).getWebServer().getPort();
            var retryRequest = open(otherPort, key, """
                    {"currency":"EUR","overdraftLimit":"25.5"}
                    """);
            var first = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            var retry = client.sendAsync(retryRequest, HttpResponse.BodyHandlers.ofString());
            assertReplay(first.get(20, TimeUnit.SECONDS), retry.get(20, TimeUnit.SECONDS), key);
        }
    }

    private void assertReplay(HttpResponse<String> created, HttpResponse<String> replayed, String key)
            throws Exception {
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(replayed.body()).isEqualTo(created.body());
        assertThat(replayed.headers().firstValue("Location"))
                .isEqualTo(created.headers().firstValue("Location"));
        assertThat(created.body()).contains("\"balance\":\"0.00\"", "\"overdraftLimit\":\"25.50\"");

        String location = created.headers().firstValue("Location").orElseThrow();
        var read = send(HttpRequest.newBuilder(uri(location)).GET().build());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(created.body());

        var conflict = send(open(key, """
                {"currency":"EUR","overdraftLimit":"25.51"}
                """));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("idempotency_conflict");
    }

    @Test
    void validatesCurrencyAndPrecision() throws Exception {
        assertInvalid("{\"currency\":\"ABC\"}", "invalid_currency");
        assertInvalid("{\"currency\":\"GBP\"}", "unsupported_currency");
        assertInvalid("{\"currency\":\"JPY\",\"overdraftLimit\":\"0.1\"}", "invalid_precision");
        assertInvalid("{\"currency\":\"EUR\",\"overdraftLimt\":\"25.00\"}", "unknown_field");
        assertInvalid("{\"currency\":\"USD\",\"currency\":\"EUR\"}", "invalid_body");
        assertInvalid("{\"currency\":\"EUR\"} {}", "invalid_body");

        var settlement = send(HttpRequest.newBuilder(uri(
                "/v1/accounts/00000000-0000-0000-0000-000000000003")).GET().build());
        assertThat(settlement.statusCode()).isEqualTo(200);
        assertThat(settlement.body()).contains("\"currency\":\"JPY\"", "\"overdraftLimit\":null");
    }

    private void assertInvalid(String body, String code) throws Exception {
        var response = send(open(UUID.randomUUID().toString(), body));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains(code);
    }

    private HttpRequest open(String key, String body) {
        return open(port, key, body);
    }

    private HttpRequest open(int targetPort, String key, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + "/v1/accounts"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
