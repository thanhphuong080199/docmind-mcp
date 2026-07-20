package com.docmind.mcp;

import java.util.UUID;

import com.docmind.summary.SummaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryToolsTest {

    @Mock
    SummaryService summaryService;

    @InjectMocks
    SummaryTools tools;

    @Test
    void returnsSummary() {
        UUID id = UUID.randomUUID();
        when(summaryService.getSummary(id)).thenReturn("A tidy summary.");

        assertThat(tools.getDocSummary(id.toString())).isEqualTo("A tidy summary.");
    }

    @Test
    void unknownIdReturnsError() {
        UUID id = UUID.randomUUID();
        when(summaryService.getSummary(id)).thenThrow(new IllegalArgumentException("No document with id " + id));

        assertThat(tools.getDocSummary(id.toString())).startsWith("Error:");
    }

    @Test
    void invalidUuidReturnsError() {
        assertThat(tools.getDocSummary("nope")).startsWith("Error:");
    }
}
