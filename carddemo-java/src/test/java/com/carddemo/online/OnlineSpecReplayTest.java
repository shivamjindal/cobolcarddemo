package com.carddemo.online;

import com.carddemo.batch.ReportSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Replays the spec of every installed program except COSGN00C (see SignonReplayTest) through the
 * REST adapter. given.commarea becomes the session's Conversation; xctl-from is a GET and reenter a
 * POST with the AID. ReportSubmission is a mock, so no TRANREPT run reads the datasets while the next case resets them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class OnlineSpecReplayTest {

    private static final Tally TALLY = new Tally();

    @Autowired
    MockMvc mvc;

    @Autowired
    Screens screens;

    @Autowired
    Datasets datasets;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    ReportSubmission reports;

    @TestFactory
    Stream<DynamicContainer> replay() {
        return Arrays.stream(Program.values()).filter(screens::installed).map(program -> dynamicContainer(
                program.name(), SpecCase.load(program.name()).stream()
                        .map(c -> dynamicTest(c.toString(), () -> replay(c)))));
    }

    @AfterAll
    static void printTally() {
        TALLY.print();
    }

    private void replay(SpecCase c) throws Exception {
        if (c.kind() == SpecCase.Kind.SKIPPED) {
            TALLY.record(c);
            abort(c.reason());
        }
        datasets.reset(c.files());
        var before = datasets.snapshot();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequestBuilder request = request(c, session);
        MockHttpServletResponse response = datasets.with(c.fileConditions(), () -> mvc.perform(request).andReturn()).getResponse();
        c.verify(observe(response, session, datasets.effectsSince(before)), datasets);
        TALLY.record(c);
    }

    private MockHttpServletRequestBuilder request(SpecCase c, MockHttpSession session) throws Exception {
        String path = "/api/screens/" + c.program();
        if (c.entry().equals("first-time")) {
            return get(path);
        }
        Conversation conversation = c.conversation();
        session.setAttribute(Conversation.KEY, conversation);
        MockHttpServletRequestBuilder request = c.entry().equals("xctl-from") ? get(path)
                : post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("aid", c.aid(), "fields", c.fields())));
        return request.session(session).with(user(conversation.user().id())).with(csrf());
    }

    private Observed observe(MockHttpServletResponse response, MockHttpSession session,
                             List<Datasets.Effect> effects) throws Exception {
        Conversation conversation = session.isInvalid() ? null : (Conversation) session.getAttribute(Conversation.KEY);
        int status = response.getStatus();
        if (status == 303) {
            String to = response.getHeader("Location").replaceFirst("^/api/screens/", "");
            return new Observed(status, "XCTL " + to, null, null, Map.of(), conversation, effects);
        }
        if (status == 401) {
            return new Observed(status, "XCTL COSGN00C", null, null, Map.of(), null, effects);
        }
        if (status != 200) {
            return new Observed(status, null, null, null, Map.of(), conversation, effects);
        }
        JsonNode body = json.readTree(response.getContentAsString());
        String message = body.path("message").path("text").textValue();
        if (body.path("signedOff").asBoolean()) {
            return new Observed(status, "XCTL COSGN00C", message, null, Map.of(), null, effects);
        }
        Map<String, String> fields = new HashMap<>();
        body.path("fields").properties().forEach(f -> fields.put(f.getKey(), f.getValue().asText()));
        return new Observed(status, "RETURN-TRANSID " + body.path("transid").asText(), message,
                body.path("cursor").textValue(), fields, conversation, effects);
    }
}
