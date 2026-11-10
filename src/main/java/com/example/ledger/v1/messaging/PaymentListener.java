package com.example.ledger.v1.messaging;

import java.util.Set;

import com.example.ledger.v1.exception.LedgerException;
import com.example.ledger.v1.model.LedgerCurrency;
import com.example.ledger.v1.service.TransactionService;
import com.example.ledger.v1.util.RequestChecks;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class PaymentListener {
    private static final Set<String> FIELDS = Set.of("eventId", "paymentId", "merchantAccountId", "amount", "currency");

    private final TransactionService transactions;
    private final JsonMapper json;

    PaymentListener(TransactionService transactions, JsonMapper json) {
        this.transactions = transactions;
        this.json = json;
    }

    @RabbitListener(queues = PaymentMessaging.QUEUE, ackMode = "AUTO")
    public void receive(Message message) {
        try {
            JsonNode payload = json.readTree(message.getBody());
            if (payload == null || !payload.isObject() || !payload.propertyNames().equals(FIELDS)) {
                throw LedgerException.invalid("body", "invalid_payment", "Payment message must contain exactly the declared fields.");
            }
            identifier(payload, "eventId");
            String paymentId = identifier(payload, "paymentId");
            String merchantId = text(payload, "merchantAccountId");
            var merchant = RequestChecks.uuid(merchantId, "merchantAccountId");
            LedgerCurrency currency = LedgerCurrency.parse(text(payload, "currency"));
            // AUTO acknowledgement occurs only after the transactional service returns successfully.
            transactions.settle(paymentId, merchant, text(payload, "amount"), currency);
        } catch (JacksonException | LedgerException exception) {
            throw new AmqpRejectAndDontRequeueException("Invalid settled payment: " + exception.getMessage(), exception);
        }
    }

    private static String identifier(JsonNode payload, String field) {
        String value = text(payload, field);
        if (!value.matches("[!-~]{1,128}")) {
            throw LedgerException.invalid(field, "invalid_identifier", field + " must contain 1–128 printable ASCII characters.");
        }
        return value;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isString()) {
            throw LedgerException.invalid(field, "invalid_type", field + " must be a string.");
        }
        return value.asString();
    }
}
