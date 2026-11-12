package com.carddemo.online;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.net.URI;
import java.util.Map;

/** REST adapter for every installed program: GET arrives, POST sends an AID with fields. */
@RestController
@RequestMapping("/api/screens/{program}")
class ScreenApi {

    record Input(Aid aid, Map<String, String> fields) {
    }

    record SignedOff(boolean signedOff, Message message) {
    }

    private final Screens screens;

    ScreenApi(Screens screens) {
        this.screens = screens;
    }

    @GetMapping
    ResponseEntity<?> arrive(@PathVariable String program, @SessionAttribute(Conversation.KEY) Conversation conversation,
                             HttpServletRequest request, HttpServletResponse response) {
        Program p = screens.program(program);
        return respond(p, screens.arrive(p, conversation), request, response);
    }

    @PostMapping
    ResponseEntity<?> receive(@PathVariable String program, @RequestBody Input input,
                              @SessionAttribute(Conversation.KEY) Conversation conversation,
                              HttpServletRequest request, HttpServletResponse response) {
        Program p = screens.program(program);
        Aid aid = input.aid() == null ? Aid.ENTER : input.aid();
        Map<String, String> fields = input.fields() == null ? Map.of() : input.fields();
        return respond(p, screens.receive(p, conversation, aid, fields), request, response);
    }

    private ResponseEntity<?> respond(Program program, Outcome outcome, HttpServletRequest request,
                                      HttpServletResponse response) {
        return switch (outcome) {
            case Outcome.Show show -> ResponseEntity.ok(screens.render(program, show));
            case Outcome.Transfer transfer -> ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(request.getContextPath() + "/api/screens/" + transfer.to().name())).build();
            case Outcome.SignOff signOff -> {
                new SecurityContextLogoutHandler().logout(request, response, null);
                yield ResponseEntity.ok(new SignedOff(true, signOff.message()));
            }
        };
    }
}
