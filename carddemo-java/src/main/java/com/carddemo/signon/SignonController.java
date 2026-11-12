package com.carddemo.signon;

import com.carddemo.online.Aid;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenPages;
import com.carddemo.online.Screens;
import com.carddemo.users.Credentials;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * COSGN00C, and the identity provider's login page. A good sign-on resumes the OIDC authorization
 * request that sent the browser here; the app session starts when that code flow completes.
 */
@Controller
@RequestMapping("/signon")
class SignonController {

    static final HttpSessionSecurityContextRepository IDP_CONTEXT = new HttpSessionSecurityContextRepository();

    static {
        IDP_CONTEXT.setSpringSecurityContextKey("CARDDEMO_IDP_SECURITY_CONTEXT");
    }

    private final Screens screens;
    private final AuthenticationManager authentication;
    private final RequestCache authorizationRequests = new HttpSessionRequestCache();

    SignonController(Screens screens, AuthenticationManager authentication) {
        this.screens = screens;
        this.authentication = authentication;
    }

    @GetMapping
    String show(Model model) {
        if (!model.containsAttribute("screen")) {
            model.addAttribute("screen", screens.render(Program.COSGN00C, new Outcome.Show(Map.of(), null, "USERID")));
        }
        return Program.COSGN00C.name();
    }

    @PostMapping
    ModelAndView submit(@RequestParam(defaultValue = "ENTER") Aid aid,
                        @RequestParam(name = "USERID", defaultValue = "") String userId,
                        @RequestParam(name = "PASSWD", defaultValue = "") String password,
                        HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        userId = userId.stripTrailing();
        return switch (aid) {
            case ENTER -> signOn(userId, password.stripTrailing(), request, response, redirect);
            case PF3 -> ScreenPages.signOff(Message.THANK_YOU, request, response, redirect);
            default -> redisplay(userId, Message.INVALID_KEY, "USERID", redirect);
        };
    }

    private ModelAndView signOn(String userId, String password, HttpServletRequest request,
                                HttpServletResponse response, RedirectAttributes redirect) {
        if (userId.isBlank()) {
            return redisplay(userId, Message.error("Please enter User ID ..."), "USERID", redirect);
        }
        if (password.isBlank()) {
            return redisplay(userId, Message.error("Please enter Password ..."), "PASSWD", redirect);
        }
        try {
            var user = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    Credentials.normalize(userId), Credentials.normalize(password)));
            request.changeSessionId();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(user);
            IDP_CONTEXT.saveContext(context, request, response);
            SavedRequest authorize = authorizationRequests.getRequest(request, response);
            return new ModelAndView(ScreenPages.seeOther(authorize == null ? "/" : authorize.getRedirectUrl()));
        } catch (UsernameNotFoundException e) {
            return redisplay(userId, Message.error("User not found. Try again ..."), "USERID", redirect);
        } catch (BadCredentialsException e) {
            return redisplay(userId, Message.error("Wrong Password. Try again ..."), "PASSWD", redirect);
        } catch (AuthenticationException e) {
            return redisplay(userId, Message.error("Unable to verify the User ..."), "USERID", redirect);
        }
    }

    private ModelAndView redisplay(String userId, Message message, String cursor, RedirectAttributes redirect) {
        redirect.addFlashAttribute("screen",
                screens.render(Program.COSGN00C, new Outcome.Show(Map.of("USERID", userId), message, cursor)));
        return new ModelAndView(ScreenPages.seeOther("/signon"));
    }
}
