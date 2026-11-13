package com.carddemo.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** processed_messages: the puts each committed request made, so a redelivered request can be answered again. */
@Repository
class ProcessedMessages {

    private static final TypeReference<List<MqMessage.Put>> PUTS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ProcessedMessages(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<List<MqMessage.Put>> puts(String program, String messageId) {
        return jdbc.query("SELECT puts::text FROM processed_messages WHERE program = ? AND message_id = ?",
                (rs, n) -> read(rs.getString(1)), program, messageId).stream().findFirst();
    }

    void record(String program, String messageId, List<MqMessage.Put> puts) {
        jdbc.update("INSERT INTO processed_messages (program, message_id, puts) VALUES (?, ?, CAST(? AS jsonb))",
                program, messageId, write(puts));
    }

    private List<MqMessage.Put> read(String text) {
        try {
            return json.readValue(text, PUTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(List<MqMessage.Put> puts) {
        try {
            return json.writeValueAsString(puts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
