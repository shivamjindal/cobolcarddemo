package com.carddemo.security;

import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Component;

import com.carddemo.identity.SignedOnUser;
import com.carddemo.online.TerminalIdentity;

/** TerminalIdentity on Spring Security, with the objects a login filter and a logout handler use. */
@Component
final class SpringSecurityTerminalIdentity implements TerminalIdentity {

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy signInStrategy;
    private final CookieCsrfTokenRepository csrfTokens;
    private final ApplicationEventPublisher events;

    SpringSecurityTerminalIdentity(SecurityContextRepository contexts, SessionAuthenticationStrategy signInStrategy,
            CookieCsrfTokenRepository csrfTokens, ApplicationEventPublisher events) {
        this.contexts = contexts;
        this.signInStrategy = signInStrategy;
        this.csrfTokens = csrfTokens;
        this.events = events;
    }

    @Override
    public Optional<SignedOnUser> current() {
        return Optional.ofNullable(holder.getContext().getAuthentication())
                .map(Authentication::getPrincipal)
                .filter(SignedOnUser.class::isInstance)
                .map(SignedOnUser.class::cast);
    }

    @Override
    public void signIn(SignedOnUser user, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        signInStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contexts.saveContext(context, request, response);
        events.publishEvent(new AuthenticationSuccessEvent(authentication));
    }

    @Override
    public void signOff(HttpServletRequest request, HttpServletResponse response) {
        holder.clearContext();
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true);
        csrfTokens.saveToken(null, request, response);
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
    }
}
