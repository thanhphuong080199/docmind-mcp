package com.docmind.mcp;

import java.util.List;

import com.docmind.search.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchToolsTest {

    @Mock
    SearchService searchService;

    @InjectMocks
    SearchTools searchTools;

    @Test
    void delegatesWithDefaults() {
        List<SearchService.SearchResult> expected = List.of(
                new SearchService.SearchResult("id-1", "file:///a.md", "content", 0.9));
        when(searchService.search("query", 5, null)).thenReturn(expected);

        assertThat(searchTools.searchDocs("query", null, null)).isEqualTo(expected);
    }

    @Test
    void clampsTopKBetween1And20() {
        searchTools.searchDocs("q", 100, null);
        verify(searchService).search(eq("q"), eq(20), eq(null));

        searchTools.searchDocs("q", 0, "some-doc");
        verify(searchService).search(eq("q"), eq(5), eq("some-doc"));
    }
}
