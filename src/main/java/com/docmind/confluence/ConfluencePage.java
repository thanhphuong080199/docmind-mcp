package com.docmind.confluence;

/**
 * A Confluence page fetched from the v2 REST API.
 *
 * @param body storage-format XHTML (parsed as-is by JsoupDocumentReader)
 * @param webUrl full clickable browser URL (with title slug)
 */
public record ConfluencePage(String id, String spaceKey, String title, String body, String webUrl) {
}
