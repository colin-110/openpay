package com.openpay.events.dlq;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dead-letter queue, over HTTP, on the operator tier.
 *
 * <p>Registered as a bean by {@link DeadLetterAutoConfiguration} rather than component-scanned,
 * because this package sits outside every application's scan. Spring MVC maps any bean carrying
 * {@code @RequestMapping}, so declaring it works exactly as scanning would.
 *
 * <p>Replaying an event is not minting a credential, so this sits with the ledger and settlement
 * runs on the ops token rather than the admin token. It is still a real action — it puts messages
 * back into the payment flow — which is why nothing here happens by accident: peek is a GET,
 * replaying and discarding are separate POSTs, and both are bounded.
 */
@RestController
@RequestMapping("/internal/dlq")
public class DeadLetterController {

    private final DeadLetterAdmin admin;

    public DeadLetterController(DeadLetterAdmin admin) {
        this.admin = admin;
    }

    /** Which topics this service can act on. */
    @GetMapping("/topics")
    public List<String> topics() {
        return admin.knownTopics();
    }

    @GetMapping
    public List<DeadLetterRecord> peek(
            @RequestParam("topic") String topic,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return admin.peek(topic, limit);
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(
            @RequestParam("topic") String topic,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return result("replayed", topic, admin.replay(topic, limit));
    }

    @PostMapping("/discard")
    public Map<String, Object> discard(
            @RequestParam("topic") String topic,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return result("discarded", topic, admin.discard(topic, limit));
    }

    @ExceptionHandler(UnknownDeadLetterTopicException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownTopic(UnknownDeadLetterTopicException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "unknown_dlq_topic");
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private Map<String, Object> result(String verb, String topic, int count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topic);
        body.put(verb, count);
        return body;
    }
}
