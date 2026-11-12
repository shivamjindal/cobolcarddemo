package com.carddemo.signon;

import com.carddemo.online.Conversation;
import com.carddemo.online.Program;
import com.carddemo.online.SignedOnUser;
import com.carddemo.online.UserType;
import com.carddemo.users.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Two filter chains in one app. The identity provider (Spring Authorization Server, login page
 * {@code /signon}) keeps its sign-on under its own session key; the app is an OIDC client of it, so
 * only a completed code flow signs a user into the screens.
 */
@Configuration
class SecurityConfig {

    static final String USR_TYPE = "usr_type";
    private static final String REGISTRATION = "carddemo";
    private static final String LOGIN = "/oauth2/authorization/" + REGISTRATION;
    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    private final String baseUrl;
    private final String clientSecret = randomSecret();

    SecurityConfig(@Value("${carddemo.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Bean
    @Order(1)
    SecurityFilterChain identityProvider(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer server = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(new OrRequestMatcher(server.getEndpointsMatcher(), PATHS.matcher("/signon")))
                .with(server, s -> s.oidc(oidc -> {}))
                .authorizeHttpRequests(a -> a.requestMatchers("/signon").permitAll().anyRequest().authenticated())
                .securityContext(c -> c.securityContextRepository(SignonController.IDP_CONTEXT))
                .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/signon")));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain app(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .requestMatchers("/signed-off", "/error", "/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(o -> o.loginPage(LOGIN).successHandler(this::signedOn))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), PATHS.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint(LOGIN), AnyRequestMatcher.INSTANCE))
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(SecurityConfig::issueCsrfCookie, CsrfFilter.class);
        return http.build();
    }

    // REST clients echo the XSRF-TOKEN cookie as X-XSRF-TOKEN, so set it on every response, not only on rendered forms.
    private static void issueCsrfCookie(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
        chain.doFilter(request, response);
    }

    private void signedOn(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        OidcUser oidc = (OidcUser) authentication.getPrincipal();
        SignedOnUser user = new SignedOnUser(oidc.getSubject(), UserType.stored(oidc.getClaimAsString(USR_TYPE)));
        request.getSession().setAttribute(Conversation.KEY, new Conversation(user, Program.COSGN00C));
        response.sendRedirect(request.getContextPath() + "/screens/" + user.type().menu().name());
    }

    /** Callers normalize the ID and password first (Credentials.normalize); the one authority is the type code. */
    @Bean
    AuthenticationManager signonAuthentication(UserRepository users) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(id -> users.find(id)
                .map(u -> User.withUsername(u.id()).password(u.passwordHash()).authorities(u.type().code).build())
                .orElseThrow(() -> new UsernameNotFoundException(id)));
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        // COSGN00C tells "User not found" apart from "Wrong Password".
        provider.setHideUserNotFoundExceptions(false);
        return new ProviderManager(provider);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> userTypeClaim() {
        return context -> context.getClaims()
                .claim(USR_TYPE, context.getPrincipal().getAuthorities().iterator().next().getAuthority());
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer(baseUrl).build();
    }

    @Bean
    RegisteredClientRepository registeredClients() {
        return new InMemoryRegisteredClientRepository(RegisteredClient.withId(REGISTRATION)
                .clientId(REGISTRATION)
                .clientSecret("{noop}" + clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri())
                .scope(OidcScopes.OPENID)
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .build());
    }

    @Bean
    ClientRegistrationRepository clientRegistrations() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId(REGISTRATION)
                .clientId(REGISTRATION)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri())
                .scope(OidcScopes.OPENID)
                .authorizationUri(baseUrl + "/oauth2/authorize")
                .tokenUri(baseUrl + "/oauth2/token")
                .jwkSetUri(baseUrl + "/oauth2/jwks")
                .issuerUri(baseUrl)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build());
    }

    // SAS matches a redirect URI exactly, so it is absolute; browse the app at carddemo.base-url.
    private String redirectUri() {
        return baseUrl + "/login/oauth2/code/" + REGISTRATION;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
