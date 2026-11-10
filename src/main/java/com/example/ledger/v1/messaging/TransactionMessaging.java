package com.example.ledger.v1.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class TransactionMessaging {
    static final String EXCHANGE = "ledger.transactions";
    static final String ROUTING_KEY = "transaction.recorded.v1";
    static final String QUEUE = "ledger.transaction-recorded.v1";

    @Bean
    Declarables transactionTopology() {
        var exchange = new TopicExchange(EXCHANGE);
        var queue = QueueBuilder.durable(QUEUE).build();
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));
    }
}
