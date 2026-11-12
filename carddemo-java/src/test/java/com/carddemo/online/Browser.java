package com.carddemo.online;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** Keeps cookies and follows redirects with GET the way a browser does, so a test walks the whole OIDC flow. */
final class Browser {

    record Page(URI uri, int status, Document html) {

        String path() {
            return uri.getPath();
        }

        String csrf() {
            return html.select("input[name=_csrf]").val();
        }

        String testid(String id) {
            return html.select("[data-testid=" + id + "]").text();
        }
    }

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;

    Browser(String base) {
        this.base = URI.create(base);
    }

    Page get(String path) throws IOException, InterruptedException {
        return follow(HttpRequest.newBuilder(base.resolve(path)).GET().build());
    }

    Page post(String path, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return follow(HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name))
                .map(java.net.HttpCookie::getValue).findFirst().orElse(null);
    }

    private Page follow(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        for (int hops = 0; response.statusCode() / 100 == 3; hops++) {
            if (hops == 10) {
                throw new IllegalStateException("Redirect loop at " + response.uri());
            }
            URI next = response.uri().resolve(response.headers().firstValue("Location").orElseThrow());
            response = http.send(HttpRequest.newBuilder(next).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        return new Page(response.uri(), response.statusCode(), Jsoup.parse(response.body(), response.uri().toString()));
    }
}
