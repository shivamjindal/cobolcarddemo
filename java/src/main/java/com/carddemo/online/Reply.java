package com.carddemo.online;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * What the terminal shows after a task. Package-private so that no feature package depends on the
 * JSON shape.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({@JsonSubTypes.Type(ScreenReply.class), @JsonSubTypes.Type(TextReply.class),
        @JsonSubTypes.Type(EndedReply.class)})
sealed interface Reply extends Serializable permits ScreenReply, TextReply, EndedReply {
    long turn();
}

/**
 * A map on screen. {@code fields} holds every field whose shown value is not blank, {@code colors}
 * the current color of every field that has one, {@code protectedFields} the fields the user cannot
 * type into now, and {@code hiddenFields} the BMS DRK fields.
 */
@JsonTypeName("screen")
@JsonInclude(JsonInclude.Include.ALWAYS)
record ScreenReply(long turn, String screen, MapRef map, Map<String, String> fields, Map<String, Color> colors,
        List<String> protectedFields, List<String> hiddenFields, String cursor, List<ActionRef> actions)
        implements Reply {}

/** SEND TEXT while the conversation goes on. */
@JsonTypeName("text")
record TextReply(long turn, String screen, String text, List<ActionRef> actions) implements Reply {}

/** SEND TEXT and RETURN without a transaction id: the conversation is over and the session signed off. */
@JsonTypeName("ended")
record EndedReply(long turn, String text) implements Reply {}

record MapRef(String mapset, String name) implements Serializable {}

record ActionRef(String name, Key key) implements Serializable {}

/** Body of {@code POST /api/screens/{screen}/{action}}. {@code key} is required only for invalid-key. */
record ActionRequest(Long turn, Map<String, String> fields, Key key) {}

/** Body of {@code POST /api/session}. */
record StartRequest(String transid) {}
