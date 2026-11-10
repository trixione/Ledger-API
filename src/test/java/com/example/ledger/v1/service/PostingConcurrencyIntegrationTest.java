package com.example.ledger.v1.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.ledger.LedgerApplication;
import com.example.ledger.v1.exception.LedgerException;
import com.example.ledger.v1.model.LedgerTransaction;
import com.example.ledger.v1.model.LedgerTransaction.Direction;
import com.example.ledger.v1.model.LedgerTransaction.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LEDGER_MYSQL_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "ledger.outbox.enabled=false"})
class PostingConcurrencyIntegrationTest {
    private static final UUID SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private AccountService accounts;
    @Autowired private TransactionService first;
    @Autowired private JdbcClient jdbc;

    @Test
    void preservesBalancesAndReplaysWritesAcrossInstances() throws Exception {
        try (var other = new SpringApplicationBuilder(LedgerApplication.class).web(WebApplicationType.NONE)
                .run("--ledger.outbox.enabled=false", "--spring.rabbitmq.listener.simple.auto-startup=false")) {
            var second = other.getBean(TransactionService.class);
            UUID a = open();
            UUID b = open();
            first.post(key(), "Fund A", transfer(SETTLEMENT, a, "10.00"));
            first.post(key(), "Fund B", transfer(SETTLEMENT, b, "10.00"));
            together(() -> first.post(key(), "A to B", transfer(a, b, "2.00")),
                    () -> second.post(key(), "B to A", transfer(b, a, "3.00")));
            assertThat(accounts.get(a).balance()).isEqualTo("11.00");
            assertThat(accounts.get(b).balance()).isEqualTo("9.00");

            var competing = together(() -> debit(first, a), () -> debit(second, a));
            assertThat(competing).containsExactlyInAnyOrder("recorded", "overdraft_exceeded");
            assertThat(accounts.get(a).balance()).isEqualTo("4.00");

            String postingKey = key();
            var entries = transfer(b, a, "1.00");
            var postings = together(() -> first.post(postingKey, "Retry posting", entries),
                    () -> second.post(postingKey, "Retry posting", entries));
            assertThat(postings.get(0)).isEqualTo(postings.get(1));
            String reversalKey = key();
            UUID posted = postings.get(0).id();
            var reversals = together(() -> first.reverse(reversalKey, posted),
                    () -> second.reverse(reversalKey, posted));
            assertThat(reversals.get(0)).isEqualTo(reversals.get(1));
            assertThat(accounts.get(a).balance()).isEqualTo("4.00");
            assertThat(accounts.get(b).balance()).isEqualTo("9.00");
            assertThat(jdbc.sql("""
                    SELECT COUNT(*) FROM (
                        SELECT currency FROM accounts GROUP BY currency HAVING SUM(balance_minor) <> 0
                    ) AS unbalanced
                    """).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("""
                    SELECT COUNT(*) FROM ledger_transactions t LEFT JOIN transaction_outbox o
                    ON t.id = o.transaction_id WHERE o.transaction_id IS NULL
                    """).query(Long.class).single()).isZero();
        }
    }

    private String debit(TransactionService service, UUID account) {
        try {
            service.post(key(), "Competing debit", transfer(account, SETTLEMENT, "7.00"));
            return "recorded";
        } catch (LedgerException exception) {
            return exception.code();
        }
    }

    private UUID open() {
        return UUID.fromString(accounts.open(key(), "EUR", null).id());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static List<Entry> transfer(UUID from, UUID to, String amount) {
        return List.of(new Entry(from, Direction.DEBIT, amount), new Entry(to, Direction.CREDIT, amount));
    }

    private static <T> List<T> together(Callable<T> a, Callable<T> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(a);
            var second = pool.submit(b);
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
