package com.carddemo.online;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

/**
 * HTML adapter for every installed program. The template is named after the program. Every POST
 * answers 303 (post/redirect/get); a redisplayed screen travels as a flash attribute.
 */
@Controller
public class ScreenPages {

    private final Screens screens;

    ScreenPages(Screens screens) {
        this.screens = screens;
    }

    @GetMapping("/")
    RedirectView home(@SessionAttribute(Conversation.KEY) Conversation conversation) {
        return seeOther("/screens/" + conversation.user().type().menu().name());
    }

    @GetMapping("/screens/{program}")
    ModelAndView show(@PathVariable String program, @SessionAttribute(Conversation.KEY) Conversation conversation,
                      Model model, HttpServletRequest request, HttpServletResponse response,
                      RedirectAttributes redirect) {
        Program p = screens.program(program);
        if (model.containsAttribute("screen")) {
            return new ModelAndView(p.name());
        }
        return respond(p, screens.arrive(p, conversation), false, request, response, redirect);
    }

    @PostMapping("/screens/{program}")
    ModelAndView submit(@PathVariable String program, @RequestParam(defaultValue = "ENTER") Aid aid,
                        @RequestParam MultiValueMap<String, String> form,
                        @SessionAttribute(Conversation.KEY) Conversation conversation,
                        HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        Program p = screens.program(program);
        Map<String, String> fields = form.toSingleValueMap();
        fields.remove("aid");
        fields.remove("_csrf");
        return respond(p, screens.receive(p, conversation, aid, fields), true, request, response, redirect);
    }

    @GetMapping("/signed-off")
    String signedOff() {
        return "signed-off";
    }

    private ModelAndView respond(Program program, Outcome outcome, boolean posted, HttpServletRequest request,
                                 HttpServletResponse response, RedirectAttributes redirect) {
        return switch (outcome) {
            case Outcome.Show show when posted -> {
                redirect.addFlashAttribute("screen", screens.render(program, show));
                yield new ModelAndView(seeOther("/screens/" + program.name()));
            }
            case Outcome.Show show -> new ModelAndView(program.name(), "screen", screens.render(program, show));
            case Outcome.Transfer transfer -> new ModelAndView(seeOther("/screens/" + transfer.to().name()));
            case Outcome.SignOff signOff -> signOff(signOff.message(), request, response, redirect);
        };
    }

    /** Ends both the app and the identity-provider sign-on, which share the session. */
    public static ModelAndView signOff(Message message, HttpServletRequest request, HttpServletResponse response,
                                       RedirectAttributes redirect) {
        new SecurityContextLogoutHandler().logout(request, response, null);
        if (message == null) {
            return new ModelAndView(seeOther("/"));
        }
        redirect.addFlashAttribute("message", message);
        return new ModelAndView(seeOther("/signed-off"));
    }

    public static RedirectView seeOther(String path) {
        RedirectView view = new RedirectView(path, true);
        view.setStatusCode(HttpStatus.SEE_OTHER);
        return view;
    }
}
