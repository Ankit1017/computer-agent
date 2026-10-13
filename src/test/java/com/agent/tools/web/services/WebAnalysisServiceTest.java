package com.agent.tools.web.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebAnalysisServiceTest {

    private final WebAnalysisService analysisService = new WebAnalysisService();

    @Test
    @DisplayName("Should extract relevant excerpts matching query from markdown text")
    void shouldExtractRelevantExcerpts() {
        String markdown = """
                # Java (programming language)
                Java is a high-level, class-based, object-oriented programming language designed to have as few implementation dependencies as possible.
                It was originally developed by James Gosling at Sun Microsystems and released in May 1995 as a core component of Sun's Java platform.
                Java applications are typically compiled to bytecode that can run on any Java virtual machine regardless of the underlying computer architecture.
                The syntax of Java is similar to C and C++, but has fewer low-level facilities than either of them. The original and reference implementation Java compilers, virtual machines, and class libraries were developed by Sun from 1991 and first released in 1995.

                # Cooking Recipes
                To bake chocolate chip cookies from scratch, preheat your oven to 375 degrees Fahrenheit.
                In a large bowl, mix granulated sugar, brown sugar, softened unsalted butter, and two large farm-fresh eggs until creamy and smooth.
                Gradually blend in all-purpose flour, baking soda, and a pinch of salt until a uniform dough forms.
                Fold in semi-sweet chocolate chips evenly. Scoop rounded tablespoons onto ungreased baking sheets spaced two inches apart.
                Bake for 9 to 11 minutes or until golden brown around the edges. Allow cookies to cool on wire racks before serving with cold milk.

                # Space Exploration
                The Apollo program was the third United States human spaceflight program carried out by NASA.
                It succeeded in landing the first humans on the Moon in 1969 with astronauts Neil Armstrong and Buzz Aldrin.
                Project Apollo was named after the Greek god of light and music, initiated by President John F. Kennedy in 1961.
                The project achieved the national goal of landing a man on the Moon and returning him safely to the Earth before the decade was out.
                """;

        String result = analysisService.analyzeContent(markdown, "what year was Java released and by whom?");

        assertThat(result)
                .contains("Relevant excerpts found for query")
                .contains("James Gosling")
                .contains("1995")
                .doesNotContain("chocolate chip cookies");
    }

    @Test
    @DisplayName("Should return error message when markdown content is null or blank")
    void shouldHandleEmptyMarkdown() {
        String nullResult = analysisService.analyzeContent(null, "query");
        assertThat(nullResult).contains("Error: Markdown content is empty");

        String blankResult = analysisService.analyzeContent("   ", "query");
        assertThat(blankResult).contains("Error: Markdown content is empty");
    }

    @Test
    @DisplayName("Should return error message when query is null or blank")
    void shouldHandleEmptyQuery() {
        String nullResult = analysisService.analyzeContent("# Some content", null);
        assertThat(nullResult).contains("Error: Query cannot be empty");

        String blankResult = analysisService.analyzeContent("# Some content", "   ");
        assertThat(blankResult).contains("Error: Query cannot be empty");
    }
}
