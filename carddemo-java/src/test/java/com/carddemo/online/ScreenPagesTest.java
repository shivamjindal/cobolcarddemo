package com.carddemo.online;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.FlashMap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTML contract every screen keeps, which browser tests and later slices rely on. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ScreenPagesTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Screens screens;

    @Autowired
    Datasets datasets;

    @TestFactory
    Stream<DynamicTest> everyScreenLabelsItsInputsAndOffersItsKeysAsButtons() {
        datasets.reset(Map.of("USRSEC", List.of(Map.of("SEC-USR-ID", "ADMIN001", "SEC-USR-FNAME", "MARGARET",
                "SEC-USR-LNAME", "GOLD", "SEC-USR-PWD", "PASSWORD", "SEC-USR-TYPE", "A"))));
        Stream<DynamicTest> signon = Stream.of(dynamicTest("COSGN00C", () -> assertScreenContract(render(get("/signon")))));
        Stream<DynamicTest> installed = Arrays.stream(Program.values()).filter(screens::installed)
                .map(p -> dynamicTest(p.name(), () -> assertScreenContract(
                        render(get("/screens/" + p).session(admin()).with(user("ADMIN001"))))));
        return Stream.concat(signon, installed);
    }

    private Document render(MockHttpServletRequestBuilder request) throws Exception {
        return Jsoup.parse(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aSubmitRedirectsAndTheFollowingGetShowsTheResult() throws Exception {
        MockHttpSession session = admin();
        MvcResult submitted = mvc.perform(post("/screens/COADM01C").param("OPTION", "9").param("aid", "ENTER")
                        .session(session).with(user("ADMIN001")).with(csrf()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/screens/COADM01C")).andReturn();

        FlashMap flash = submitted.getFlashMap();
        Document page = Jsoup.parse(mvc.perform(get("/screens/COADM01C").session(session).with(user("ADMIN001"))
                .flashAttrs(flash)).andReturn().getResponse().getContentAsString());

        assertThat(page.select("[data-testid=ERRMSG]").text()).isEqualTo("Please enter a valid option number...");
        assertThat(page.select("[data-testid=ERRMSG]").hasClass("error")).isTrue();
        assertThat(page.select("[data-testid=OPTION]").val()).isEqualTo("09");
    }

    @Test
    void aTransferRedirectsToTheTargetScreen() throws Exception {
        mvc.perform(post("/screens/COADM01C").param("OPTION", "1").session(admin()).with(user("ADMIN001")).with(csrf()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/screens/COUSR00C"));
    }

    @Test
    void programsThatAreNotInstalledAreNotFound() throws Exception {
        mvc.perform(get("/screens/COACTVWC").session(admin()).with(user("ADMIN001"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/screens/NOSUCHPG").session(admin()).with(user("ADMIN001"))).andExpect(status().isNotFound());
    }

    @Test
    void signedOutPagesStartTheSignOnFlowAndTheApiAnswers401() throws Exception {
        mvc.perform(get("/screens/COADM01C"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost/oauth2/authorization/carddemo"));
        mvc.perform(get("/api/screens/COADM01C")).andExpect(status().isUnauthorized());
    }

    private static MockHttpSession admin() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(Conversation.KEY,
                new Conversation(new SignedOnUser("ADMIN001", UserType.ADMIN), Program.COSGN00C));
        return session;
    }

    private static void assertScreenContract(Document page) {
        assertThat(page.select("[data-testid=ERRMSG]")).hasSize(1);
        assertThat(page.select("[data-testid=PGMNAME]").text()).isNotBlank();
        for (Element input : page.select("form input:not([type=hidden])")) {
            String name = input.attr("name");
            assertThat(input.attr("data-testid")).as("data-testid of %s", name).isEqualTo(name);
            assertThat(page.select("label[for=" + input.id() + "]")).as("label of %s", name).hasSize(1);
        }
        assertThat(page.select("[autofocus]").size()).isLessThanOrEqualTo(1);
        List<Element> buttons = page.select("form button");
        assertThat(buttons).isNotEmpty().allSatisfy(b -> assertThat(b.attr("name")).isEqualTo("aid"));
        assertThat(buttons.getFirst().val()).isEqualTo("ENTER");
    }
}
