package com.docmind.confluence;

import java.util.Base64;
import java.util.List;

import com.docmind.config.DocmindProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConfluenceClientTest {

    private static final String BASE = "https://c.atlassian.net";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ConfluenceClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new DocmindProperties(null, false, 0.0,
                new DocmindProperties.Confluence(BASE, "me@c.com", "tok", List.of("DOCS"), false, "PT1H"));
        client = new ConfluenceClient(builder, props);
    }

    @Test
    void spaceIdSendsBasicAuthAndResolvesId() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("me@c.com:tok".getBytes());
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces?keys=DOCS"))
                .andExpect(header("Authorization", basic))
                .andRespond(withSuccess("{\"results\":[{\"id\":\"111\",\"key\":\"DOCS\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.spaceId("DOCS")).isEqualTo("111");
        server.verify();
    }

    @Test
    void unknownSpaceThrowsClearError() {
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces?keys=NOPE"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.spaceId("NOPE"))
                .isInstanceOf(ConfluenceException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void unauthorizedSurfacedAsClearError() {
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces?keys=DOCS"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.spaceId("DOCS"))
                .isInstanceOf(ConfluenceException.class)
                .hasMessageContaining("CONFLUENCE_API_TOKEN");
    }

    @Test
    void pagesFollowsCursorAndMapsDtos() {
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces/111/pages?body-format=storage&limit=50"))
                .andRespond(withSuccess("""
                        {"results":[{"id":"456","title":"First",
                          "body":{"storage":{"value":"<p>one</p>"}},
                          "_links":{"webui":"/spaces/DOCS/pages/456/First"}}],
                         "_links":{"next":"/wiki/api/v2/spaces/111/pages?limit=50&cursor=ABC"}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces/111/pages?limit=50&cursor=ABC"))
                .andRespond(withSuccess("""
                        {"results":[{"id":"789","title":"Second",
                          "body":{"storage":{"value":"<p>two</p>"}},
                          "_links":{"webui":"/spaces/DOCS/pages/789/Second"}}],
                         "_links":{}}""",
                        MediaType.APPLICATION_JSON));

        List<ConfluencePage> pages = client.pages("111");

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).id()).isEqualTo("456");
        assertThat(pages.get(0).spaceKey()).isEqualTo("DOCS");
        assertThat(pages.get(0).title()).isEqualTo("First");
        assertThat(pages.get(0).body()).isEqualTo("<p>one</p>");
        assertThat(pages.get(0).webUrl()).isEqualTo(BASE + "/wiki/spaces/DOCS/pages/456/First");
        assertThat(pages.get(1).id()).isEqualTo("789");
        server.verify();
    }

    @Test
    void pageFetchesSingleAndMaps() {
        server.expect(requestTo(BASE + "/wiki/api/v2/pages/456?body-format=storage"))
                .andRespond(withSuccess("""
                        {"id":"456","title":"First",
                         "body":{"storage":{"value":"<p>one</p>"}},
                         "_links":{"webui":"/spaces/DOCS/pages/456/First"}}""",
                        MediaType.APPLICATION_JSON));

        ConfluencePage page = client.page("456");

        assertThat(page.spaceKey()).isEqualTo("DOCS");
        assertThat(page.body()).isEqualTo("<p>one</p>");
        server.verify();
    }

    @Test
    void missingPageThrowsClearError() {
        server.expect(requestTo(BASE + "/wiki/api/v2/pages/999?body-format=storage"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.page("999"))
                .isInstanceOf(ConfluenceException.class)
                .hasMessageContaining("999");
    }
}
