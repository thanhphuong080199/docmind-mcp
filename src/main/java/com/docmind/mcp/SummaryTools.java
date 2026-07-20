package com.docmind.mcp;

import java.util.UUID;

import com.docmind.summary.SummaryService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class SummaryTools {

    private final SummaryService summaryService;

    public SummaryTools(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @McpTool(name = "get_doc_summary", description = """
            Get a concise summary of an ingested document by id (see list_available_docs). \
            Generated once by a local LLM on first request, then cached — the first call \
            for a document can take a minute.""")
    public String getDocSummary(
            @McpToolParam(description = "Document id (UUID)") String docId) {
        try {
            return summaryService.getSummary(UUID.fromString(docId));
        }
        catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        catch (Exception e) {
            return "Error: could not summarize document " + docId + " — " + e.getMessage();
        }
    }
}
