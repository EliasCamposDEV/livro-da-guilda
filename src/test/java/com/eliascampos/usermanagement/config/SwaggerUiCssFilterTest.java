package com.eliascampos.usermanagement.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerUiCssFilterTest {

    private final SwaggerUiCssFilter filter = new SwaggerUiCssFilter();

    @Test
    void shouldInjectStylesheetMetaAndAccessibilityEnhancement() throws Exception {
        MockHttpServletResponse response = filterSwagger("/swagger-ui/index.html",
                "<html><head><title>Swagger UI</title></head><body></body></html>");

        String body = response.getContentAsString();
        assertTrue(body.contains("<meta name=\"description\""), body);
        assertTrue(body.contains("<title>Livro da Guilda - API</title>"), body);
        assertTrue(body.contains("/swagger-custom.css"), body);
        assertTrue(body.contains("lg-operations-heading"), body);
        assertTrue(body.contains("Servidor"), body);
        assertTrue(body.contains("swagger-ui__skip-link"), body);
        assertTrue(body.contains("dark-mode-toggle button"), body);
        assertTrue(body.contains("topbar a.link:not([href])"), body);
        assertTrue(body.contains("setAttribute('role', 'main')"), body);
        assertTrue(body.contains("</head>"), body);
        assertEquals("text/html;charset=UTF-8", response.getContentType());
    }

    @Test
    void shouldNotInjectTwice() throws Exception {
        String first = filterSwagger("/swagger-ui.html", "<html><head></head><body></body></html>")
                .getContentAsString();
        MockHttpServletResponse response = filterSwagger("/swagger-ui.html", first);

        String body = response.getContentAsString();
        assertEquals(1, count(body, "swagger-custom.css"));
        assertEquals(1, count(body, "<meta name=\"description\""));
    }

    @Test
    void shouldLeaveOtherResponsesUntouched() throws Exception {
        String html = "<html><head></head><body>api</body></html>";
        MockHttpServletResponse response = filterSwagger("/actuator/health", html);

        assertEquals(html, response.getContentAsString());
        assertFalse(response.getContentAsString().contains("swagger-custom.css"));
    }

    private MockHttpServletResponse filterSwagger(String uri, String html) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setContentType("text/html;charset=UTF-8");
            res.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    private static int count(String text, String needle) {
        int total = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            total++;
            from += needle.length();
        }
        return total;
    }
}
