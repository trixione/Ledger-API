package com.example.ledger.v1.messaging;

import java.util.concurrent.TimeUnit;

import com.example.ledger.v1.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;

    OutboxPublisher(OutboxRepository outbox, RabbitTemplate rabbit, PlatformTransactionManager manager) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-delay-ms:1000}")
    void publishPending() {
        try {
            for (int i = 0; i < 25; i++) {
                if (!Boolean.TRUE.equals(transaction.execute(status -> publishOne()))) {
                    return;
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("Outbox delivery failed. The pending event will be retried.", failure);
        }
    }

    private boolean publishOne() {
        var pending = outbox.lockNext();
        if (pending.isEmpty()) {
            return false;
        }
        var event = pending.orElseThrow();
        var message = MessageBuilder.withBody(event.payload().getBytes(UTF_8))
                .setMessageId(event.transactionId())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        // Each attempt has its own confirmation, while the message ID stays stable across retries.
        var correlation = new CorrelationData();
        rabbit.send(TransactionMessaging.EXCHANGE, TransactionMessaging.ROUTING_KEY, message, correlation);
        try {
            var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirmation.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("Broker did not confirm routing for " + event.transactionId());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for broker confirmation", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Failed to confirm event " + event.transactionId(), exception);
        }
        // A crash before this update commits can resend the event with the same message ID.
        outbox.markPublished(event.transactionId());
        return true;
    }
}
