package com.carddemo.online;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.online.maps.Maps;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * <pre>
 * GET  /api/session                     the reply on screen, or 404 when there is no conversation
 * POST /api/session                     {"transid":"CC00"}: sign off and start that transaction
 * POST /api/screens/{screen}/{action}   {"turn":n,"fields":{...}[,"key":"PF9"]}: the next reply
 * GET  /api/maps/{name}                 the BMS layout of a map
 * </pre>
 */
@RestController
@RequestMapping("/api")
final class ScreenController {

    private static final String DEFAULT_TRANSID = "CC00";

    private final Dispatcher dispatcher;

    ScreenController(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GetMapping("/session")
    ResponseEntity<?> current(HttpServletRequest request) {
        return toHttp(dispatcher.current(request));
    }

    @PostMapping("/session")
    ResponseEntity<?> start(@RequestBody(required = false) StartRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        String transid = body == null || body.transid() == null ? DEFAULT_TRANSID : body.transid();
        return toHttp(dispatcher.start(transid, request, response));
    }

    @PostMapping("/screens/{screen}/{action}")
    ResponseEntity<?> act(@PathVariable String screen, @PathVariable String action,
            @RequestBody(required = false) ActionRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return toHttp(dispatcher.act(screen, action, body, request, response));
    }

    @GetMapping("/maps/{name}")
    ResponseEntity<?> map(@PathVariable String name) {
        return Maps.ALL.stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .<ResponseEntity<?>>map(m -> ResponseEntity.ok(MapLayout.of(m)))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "no map " + name));
    }

    private static ResponseEntity<?> toHttp(Dispatcher.Result result) {
        return switch (result) {
            case Dispatcher.Result.Ok ok -> ResponseEntity.ok(ok.reply());
            case Dispatcher.Result.Stale stale -> ResponseEntity.status(HttpStatus.CONFLICT).body(stale.current());
            case Dispatcher.Result.NoConversation none ->
                    problem(HttpStatus.NOT_FOUND, "no conversation; POST /api/session to start one");
            case Dispatcher.Result.UnknownTransaction unknown ->
                    problem(HttpStatus.NOT_FOUND, "no transaction " + unknown.transid());
            case Dispatcher.Result.UnknownAction unknown ->
                    problem(HttpStatus.NOT_FOUND, "no action " + unknown.action() + " on this screen");
            case Dispatcher.Result.BadRequest bad -> problem(HttpStatus.BAD_REQUEST, bad.message());
            case Dispatcher.Result.Forbidden forbidden -> problem(HttpStatus.FORBIDDEN, "not allowed on this screen");
        };
    }

    private static ResponseEntity<?> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FieldLayout(String name, int row, int col, int length, List<Attribute> attributes, String initial,
            Color color, String hilight) {

        static FieldLayout of(String name, BmsField bms) {
            return new FieldLayout(name, bms.row(), bms.col(), bms.length(),
                    bms.attributes().stream().sorted().toList(), bms.initial(), bms.color(), bms.hilight());
        }
    }

    record MapLayout(String mapset, String name, List<FieldLayout> fields, List<FieldLayout> literals) {

        static <F extends Enum<F> & MapField> MapLayout of(MapId<F> map) {
            return new MapLayout(map.mapset(), map.name(),
                    map.fields().stream().map(f -> FieldLayout.of(f.name(), f.bms())).toList(),
                    map.literals().stream().map(l -> FieldLayout.of(null, l)).toList());
        }
    }
}
