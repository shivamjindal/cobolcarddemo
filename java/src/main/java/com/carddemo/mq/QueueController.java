package com.carddemo.mq;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.nio.charset.CharsetEncoder;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue manager for an operator, as the MQ explorer of a z/OS shop. Text is latin-1; ids are text padded with
 * spaces to 24 bytes and shown without trailing spaces or low-values.
 * <pre>
 * GET  /api/mq/queues/{queue}/messages   browse: every message in put order, none taken
 * POST /api/mq/queues/{queue}/messages   {"data":"...","correlId":"...","replyTo":"..."}: MQPUT1, which starts
 *                                        the queue's trigger transaction once it commits; 201 {"queue","correlId"}
 * POST /api/mq/queues/{queue}/get        MQGET without wait: the oldest message, or 204 when there is none
 * </pre>
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/api/mq/queues")
final class QueueController {

    static final int MAX_DATA = 4096;

    record PutRequest(String data, String correlId, String replyTo) {}

    record Put(String queue, String correlId) {}

    record Browsed(String msgId, String correlId, String replyTo, String data) {

        static Browsed of(Message message) {
            return new Browsed(id(message.msgId()), id(message.correlId()), message.replyToQ(),
                    new String(message.data(), ISO_8859_1));
        }
    }

    private final MessageQueues queues;

    QueueController(MessageQueues queues) {
        this.queues = queues;
    }

    @GetMapping("/{queue}/messages")
    ResponseEntity<?> browse(@PathVariable String queue) {
        if (!validName(queue)) {
            return problem(HttpStatus.BAD_REQUEST, "a queue name is 1 to 48 characters");
        }
        List<Browsed> messages = queues.browse(queue).stream().map(Browsed::of).toList();
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/{queue}/messages")
    ResponseEntity<?> put(@PathVariable String queue, @RequestBody(required = false) PutRequest body) {
        if (!validName(queue)) {
            return problem(HttpStatus.BAD_REQUEST, "a queue name is 1 to 48 characters");
        }
        if (body == null || body.data() == null) {
            return problem(HttpStatus.BAD_REQUEST, "data is required");
        }
        String correlId = body.correlId() == null ? "" : body.correlId();
        String replyTo = body.replyTo() == null ? "" : body.replyTo().stripTrailing();
        CharsetEncoder latin1 = ISO_8859_1.newEncoder();
        if (!latin1.canEncode(body.data()) || body.data().length() > MAX_DATA) {
            return problem(HttpStatus.BAD_REQUEST, "data is up to " + MAX_DATA + " latin-1 characters");
        }
        if (!latin1.canEncode(correlId) || correlId.length() > Message.ID_LENGTH) {
            return problem(HttpStatus.BAD_REQUEST, "correlId is up to 24 latin-1 characters");
        }
        if (replyTo.length() > QueueHandle.NAME_LENGTH) {
            return problem(HttpStatus.BAD_REQUEST, "replyTo is up to 48 characters");
        }
        byte[] correl = correlId.isEmpty() ? Message.NONE : padded(correlId);
        Message message = new Message(body.data().getBytes(ISO_8859_1), Message.NONE, correl, replyTo);
        Reason reason = queues.put1(queue, message, false);
        if (reason != Reason.NONE) {
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, "MQPUT1 to " + queue + " failed with MQRC " + reason.code());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new Put(queue.stripTrailing(), correlId.stripTrailing()));
    }

    @PostMapping("/{queue}/get")
    ResponseEntity<?> get(@PathVariable String queue) {
        if (!validName(queue)) {
            return problem(HttpStatus.BAD_REQUEST, "a queue name is 1 to 48 characters");
        }
        QueueHandle handle = queues.open(queue).handle().orElseThrow();
        try {
            Got got = queues.get(handle, GetOptions.waitMillis(0, false));
            return got.message().<ResponseEntity<?>>map(m -> ResponseEntity.ok(Browsed.of(m)))
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } finally {
            queues.close(handle);
        }
    }

    private static boolean validName(String queue) {
        return !queue.isBlank() && queue.length() <= QueueHandle.NAME_LENGTH;
    }

    private static byte[] padded(String text) {
        byte[] id = new byte[Message.ID_LENGTH];
        Arrays.fill(id, (byte) ' ');
        byte[] bytes = text.getBytes(ISO_8859_1);
        System.arraycopy(bytes, 0, id, 0, bytes.length);
        return id;
    }

    private static String id(byte[] id) {
        return new String(id, ISO_8859_1).replaceAll("[ \0]+$", "");
    }

    private static ResponseEntity<?> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
