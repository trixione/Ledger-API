package com.example.ledger.v1.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PaymentMessaging {
    static final String EXCHANGE = "ledger.payments";
    static final String ROUTING_KEY = "payment.settled.v1";
    static final String QUEUE = "ledger.payment-settled.v1";
    private static final String DEAD_EXCHANGE = "ledger.payments.dead";
    private static final String DEAD_QUEUE = QUEUE + ".dead";

    @Bean
    Declarables paymentTopology() {
        var exchange = new DirectExchange(EXCHANGE);
        var deadExchange = new DirectExchange(DEAD_EXCHANGE);
        var queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(ROUTING_KEY).build();
        var deadQueue = QueueBuilder.durable(DEAD_QUEUE).build();
        return new Declarables(exchange, deadExchange, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(deadExchange).with(ROUTING_KEY));
    }
}
