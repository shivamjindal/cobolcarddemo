package com.carddemo.security;

import java.util.List;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.web.util.HttpSessionMutexListener;

import com.carddemo.online.Access;
import com.carddemo.online.ProgramCatalog;

/**
 * A same-origin single-page app with a session cookie. Sign-on is a screen, not a login filter;
 * SpringSecurityTerminalIdentity stores its result with the strategies a login filter would use.
 * URL rules come from each screen's Access, and the dispatcher checks Access again on every XCTL
 * target, which no filter sees. CSRF uses the cookie XSRF-TOKEN and the header X-XSRF-TOKEN.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    CookieCsrfTokenRepository csrfTokens() {
        CookieCsrfTokenRepository tokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        tokens.setCookieCustomizer(cookie -> cookie.sameSite("Strict").path("/"));
        return tokens;
    }

    @Bean
    SecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy signInStrategy(CookieCsrfTokenRepository csrfTokens) {
        CsrfAuthenticationStrategy csrf = new CsrfAuthenticationStrategy(csrfTokens);
        csrf.setRequestHandler(new SpaCsrfTokenRequestHandler());
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), csrf));
    }

    @Bean
    ServletListenerRegistrationBean<HttpSessionMutexListener> sessionMutex() {
        return new ServletListenerRegistrationBean<>(new HttpSessionMutexListener());
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ProgramCatalog programs, CookieCsrfTokenRepository csrfTokens,
            SecurityContextRepository securityContexts) throws Exception {
        String[] anyone = programs.actionPatterns(Access.ANYONE).toArray(String[]::new);
        String[] admin = programs.actionPatterns(Access.ADMIN).toArray(String[]::new);
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/session", "/api/maps/**").permitAll()
                .requestMatchers(HttpMethod.POST, anyone).permitAll()
                .requestMatchers(HttpMethod.POST, admin).hasRole("ADMIN")
                .anyRequest().authenticated())
            .securityContext(context -> context.securityContextRepository(securityContexts))
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .requestCache(cache -> cache.disable())
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")));
        return http.build();
    }
}
