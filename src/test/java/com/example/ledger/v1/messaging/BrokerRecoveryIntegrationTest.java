package com.example.ledger.v1.messaging;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.example.ledger.LedgerApplication;
import com.example.ledger.v1.model.LedgerTransaction.Direction;
import com.example.ledger.v1.model.LedgerTransaction.Entry;
import com.example.ledger.v1.service.AccountService;
import com.example.ledger.v1.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named = "LEDGER_BROKER_TEST", matches = "true")
@SpringBootTest(properties = "ledger.outbox.enabled=false")
class BrokerRecoveryIntegrationTest {
    private static final UUID SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private AccountService accounts;
    @Autowired private TransactionService transactions;
    @Autowired private JdbcClient jdbc;
    @Autowired private AmqpAdmin admin;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private JsonMapper json;

    @Test
    void recoversPendingEventsAndContinuesAfterAnInvalidPayment() {
        String observer = "ledger.test." + UUID.randomUUID();
        var queue = new Queue(observer, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(TransactionMessaging.EXCHANGE))
                .with(TransactionMessaging.ROUTING_KEY));
        try {
            UUID merchant = UUID.fromString(accounts.open(key(), "EUR", null).id());
            var direct = transactions.post(key(), "Direct", java.util.List.of(
                    new Entry(SETTLEMENT, Direction.DEBIT, "5.00"), new Entry(merchant, Direction.CREDIT, "5.00")));
            var reversal = transactions.reverse(key(), direct.id());
            assertThat(isPublished(direct.id())).isFalse();
            assertThat(isPublished(reversal.id())).isFalse();

            String paymentId = key();
            String invalid = "{\"eventId\":\"" + paymentId + "\"}";
            publishPayment(invalid);
            publishPayment(payment(key(), paymentId, merchant));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(accounts.get(merchant).balance()).isEqualTo("7.50");
                assertThat(paymentCount(paymentId)).isEqualTo(1);
            });
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var dead = rabbit.receive(PaymentMessaging.QUEUE + ".dead", 500);
                assertThat(dead).isNotNull();
                assertThat(new String(dead.getBody(), UTF_8)).isEqualTo(invalid);
            });
            publishPayment(payment(key(), paymentId, merchant));
            UUID followingMerchant = UUID.fromString(accounts.open(key(), "EUR", null).id());
            publishPayment(payment(key(), key(), followingMerchant));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(accounts.get(followingMerchant).balance()).isEqualTo("7.50");
                assertThat(accounts.get(merchant).balance()).isEqualTo("7.50");
                assertThat(paymentCount(paymentId)).isEqualTo(1);
            });

            UUID settled = jdbc.sql("SELECT transaction_id FROM settled_payments WHERE payment_id = ?")
                    .param(paymentId.getBytes(UTF_8)).query(String.class).optional().map(UUID::fromString).orElseThrow();
            Set<String> expected = Set.of(direct.id().toString(), reversal.id().toString(), settled.toString());

            // The posting app is still alive, but its publisher was unavailable when the commits occurred.
            try (var recovered = new SpringApplicationBuilder(LedgerApplication.class)
                    .run("--server.port=0", "--ledger.outbox.enabled=true",
                            "--spring.rabbitmq.listener.simple.auto-startup=false")) {
                Set<String> observed = new HashSet<>();
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    var message = rabbit.receive(observer, 500);
                    if (message != null && expected.contains(message.getMessageProperties().getMessageId())) {
                        var payload = json.readTree(message.getBody());
                        String eventId = payload.path("eventId").asString();
                        assertThat(eventId).isEqualTo(message.getMessageProperties().getMessageId());
                        assertThat(payload.path("transactionId").asString()).isEqualTo(eventId);
                        observed.add(eventId);
                    }
                    assertThat(observed).containsExactlyInAnyOrderElementsOf(expected);
                });
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(isPublished(direct.id())).isTrue();
                    assertThat(isPublished(reversal.id())).isTrue();
                    assertThat(isPublished(settled)).isTrue();
                });
            }
            assertThat(accounts.get(merchant).balance()).isEqualTo("7.50");
        } finally {
            admin.deleteQueue(observer);
        }
    }

    private long paymentCount(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM settled_payments WHERE payment_id = ?")
                .param(id.getBytes(UTF_8)).query(Long.class).single();
    }

    private boolean isPublished(UUID id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM transaction_outbox WHERE transaction_id = ?")
                .param(id.toString()).query(Boolean.class).single();
    }

    private void publishPayment(String payload) {
        rabbit.send(PaymentMessaging.EXCHANGE, PaymentMessaging.ROUTING_KEY,
                MessageBuilder.withBody(payload.getBytes(UTF_8)).setContentType("application/json").build());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String payment(String event, String payment, UUID merchant) {
        return """
                {"eventId":"%s","paymentId":"%s","merchantAccountId":"%s","amount":"7.50","currency":"EUR"}
                """.formatted(event, payment, merchant);
    }
}
