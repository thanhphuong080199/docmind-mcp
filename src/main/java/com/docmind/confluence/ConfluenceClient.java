package com.docmind.confluence;

import java.util.ArrayList;
import java.util.List;

import com.docmind.config.DocmindProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "docmind.confluence.base-url")
public class ConfluenceClient {

    private final RestClient restClient;
    private final String baseUrl;

    public ConfluenceClient(RestClient.Builder builder, DocmindProperties properties) {
        DocmindProperties.Confluence cfg = properties.confluence();
        this.baseUrl = cfg.baseUrl();
        this.restClient = builder
                .baseUrl(cfg.baseUrl())
                .defaultHeaders(h -> h.setBasicAuth(cfg.email(), cfg.apiToken()))
                .build();
    }

    public String spaceId(String spaceKey) {
        SpacesResponse response = restClient.get()
                .uri("/wiki/api/v2/spaces?keys={key}", spaceKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> { throw authOrGeneric(resp.getStatusCode()); })
                .body(SpacesResponse.class);
        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new ConfluenceException("Confluence space not found: " + spaceKey);
        }
        return response.results().get(0).id();
    }

    public List<ConfluencePage> pages(String spaceId) {
        List<ConfluencePage> all = new ArrayList<>();
        String uri = "/wiki/api/v2/spaces/" + spaceId + "/pages?body-format=storage&limit=50";
        while (uri != null) {
            PagesResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> { throw authOrGeneric(resp.getStatusCode()); })
                    .body(PagesResponse.class);
            if (response == null) {
                break;
            }
            for (PageJson p : response.results()) {
                all.add(toPage(p));
            }
            uri = (response.links() == null) ? null : response.links().next();
        }
        return all;
    }

    public ConfluencePage page(String pageId) {
        PageJson json = restClient.get()
                .uri("/wiki/api/v2/pages/{id}?body-format=storage", pageId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    if (resp.getStatusCode().value() == 404) {
                        throw new ConfluenceException("Confluence page not found: " + pageId);
                    }
                    throw authOrGeneric(resp.getStatusCode());
                })
                .body(PageJson.class);
        if (json == null) {
            throw new ConfluenceException("Confluence page not found: " + pageId);
        }
        return toPage(json);
    }

    private ConfluencePage toPage(PageJson p) {
        String webui = (p.links() == null) ? "" : p.links().webui();
        String spaceKey = spaceKeyFromWebui(webui);
        String body = (p.body() == null || p.body().storage() == null) ? "" : p.body().storage().value();
        return new ConfluencePage(p.id(), spaceKey, p.title(), body, baseUrl + "/wiki" + webui);
    }

    private static String spaceKeyFromWebui(String webui) {
        // webui is like "/spaces/DOCS/pages/456/Title-Slug"
        String[] parts = webui.split("/");
        if (parts.length >= 3 && "spaces".equals(parts[1])) {
            return parts[2];
        }
        throw new ConfluenceException("Cannot determine space key from webui link: " + webui);
    }

    private static ConfluenceException authOrGeneric(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return new ConfluenceException(
                    "Confluence auth failed (HTTP " + code + ") — check CONFLUENCE_API_TOKEN / email");
        }
        return new ConfluenceException("Confluence request failed (HTTP " + code + ")");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpacesResponse(List<SpaceRef> results) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpaceRef(String id, String key) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PagesResponse(List<PageJson> results, @JsonProperty("_links") Links links) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PageJson(String id, String title, Body body, @JsonProperty("_links") Links links) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Body(Storage storage) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Storage(String value) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Links(String next, String webui) { }
}
