package com.example.ledger.v1.messaging;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.example.ledger.v1.service.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "LEDGER_MYSQL_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class TransactionIntegrationTest {
    private static final String SETTLEMENT = "00000000-0000-0000-0000-000000000001";

    @LocalServerPort
    private int port;

    @Autowired
    private AccountService accounts;

    @Autowired
    private PaymentListener payments;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void postsReplaysReadsAndReversesWithoutChangingOriginal() throws Exception {
        String merchant = account();
        String key = key();
        String body = posting(SETTLEMENT, merchant, "125.50", "125.50");
        var created = send(post("/v1/transactions", key, body));
        assertThat(created.statusCode()).isEqualTo(201);
        String path = created.headers().firstValue("Location").orElseThrow();
        String id = json.readTree(created.body()).path("id").asString();
        assertThat(send(post("/v1/transactions", key, body)).body()).isEqualTo(created.body());
        assertThat(send(post("/v1/transactions", key, posting(SETTLEMENT, merchant, "1", "1"))).statusCode())
                .isEqualTo(409);
        assertThat(balance(merchant)).isEqualTo("125.50");
        String payload = jdbc.sql("SELECT payload FROM transaction_outbox WHERE transaction_id = ?")
                .param(id).query(String.class).single();
        var event = json.readTree(payload);
        assertThat(event.path("eventId").asString()).isEqualTo(id);
        assertThat(event.path("recordedAt").isString()).isTrue();

        String reversalKey = key();
        var first = http.sendAsync(post(path + "/reversals", reversalKey, ""), HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(post(path + "/reversals", key(), ""), HttpResponse.BodyHandlers.ofString());
        var a = first.get(20, TimeUnit.SECONDS);
        var b = second.get(20, TimeUnit.SECONDS);
        assertThat(new int[]{a.statusCode(), b.statusCode()}).containsExactlyInAnyOrder(201, 409);
        var reversal = a.statusCode() == 201 ? a : b;
        assertThat(json.readTree(reversal.body()).path("reversesId").asString()).isEqualTo(id);
        String reversalPath = reversal.headers().firstValue("Location").orElseThrow();
        assertThat(send(post(reversalPath + "/reversals", key(), "")).statusCode()).isEqualTo(400);
        assertThat(send(HttpRequest.newBuilder(uri(path)).GET().build()).body()).isEqualTo(created.body());
        assertThat(balance(merchant)).isEqualTo("0.00");
    }

    @Test
    void refusesUnbalancedAndOverdraftWritesAndReleasesTheKey() throws Exception {
        String merchant = account();
        String key = key();
        var unbalanced = send(post("/v1/transactions", key, posting(SETTLEMENT, merchant, "2", "1")));
        assertThat(unbalanced.statusCode()).isEqualTo(400);
        assertThat(unbalanced.body()).contains("unbalanced_transaction");
        var overdraft = send(post("/v1/transactions", key, posting(merchant, SETTLEMENT, "1", "1")));
        assertThat(overdraft.statusCode()).isEqualTo(400);
        assertThat(overdraft.body()).contains("overdraft_exceeded");
        assertThat(balance(merchant)).isEqualTo("0.00");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?")
                .param(merchant).query(Long.class).single()).isZero();
        assertThat(send(post("/v1/transactions", key, posting(SETTLEMENT, merchant, "1", "1"))).statusCode())
                .isEqualTo(201);
    }

    @Test
    void rejectsPoisonPaymentsAndDeduplicatesAcrossEventIds() {
        String merchant = account();
        String payment = key();
        assertThatThrownBy(() -> payments.receive(new Message("{".getBytes(UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> payments.receive(payment("bad", payment, merchant, "USD")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        payments.receive(payment("first", payment, merchant, "EUR"));
        payments.receive(payment("redelivery", payment, merchant, "EUR"));
        assertThat(balance(merchant)).isEqualTo("125.50");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM settled_payments WHERE payment_id = ?")
                .param(payment.getBytes(UTF_8)).query(Long.class).single()).isEqualTo(1);
    }

    private String account() {
        return accounts.open(key(), "EUR", null).id();
    }

    private String balance(String id) {
        return accounts.get(UUID.fromString(id)).balance();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String posting(String debit, String credit, String debitAmount, String creditAmount) {
        return """
                {"description":"Example posting","entries":[
                  {"accountId":"%s","direction":"DEBIT","amount":"%s"},
                  {"accountId":"%s","direction":"CREDIT","amount":"%s"}]}
                """.formatted(debit, debitAmount, credit, creditAmount);
    }

    private static Message payment(String event, String payment, String merchant, String currency) {
        return new Message("""
                {"eventId":"%s","paymentId":"%s","merchantAccountId":"%s","amount":"125.50","currency":"%s"}
                """.formatted(event, payment, merchant, currency).getBytes(UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpRequest post(String path, String key, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
