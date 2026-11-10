package com.example.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false", "ledger.outbox.enabled=false"}
)
class LedgerApplicationTest {
    @Test
    void startsEmbeddedServer() {
    }
}
