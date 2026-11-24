package com.eliascampos.usermanagement.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Injeta no HTML do Swagger UI a folha de estilo propria, o meta description, o titulo
 * da pagina e um ajuste progressivo de acessibilidade (rotulo do seletor de servidor,
 * skip link, landmark principal, rotulo do botao de tema e ordem de titulos).
 * O buffer evita o conflito entre getOutputStream() e getWriter() do ContentCachingResponseWrapper.
 */
@Component
public class SwaggerUiCssFilter extends OncePerRequestFilter {

    private static final String DOCUMENT_TITLE = "Livro da Guilda - API";
    private static final String SWAGGER_TITLE = "<title>Swagger UI</title>";
    private static final String HEAD_INJECTION = """
            <meta name="description" content="API de gerenciamento de usuarios com JWT, auditoria e demo publica somente leitura.">
            <script>
            (function () {
              function hasName(node) {
                return Boolean(((node.getAttribute('aria-label') || '') + ' ' +
                  (node.getAttribute('title') || '') + ' ' + (node.textContent || '')).trim());
              }
              function enhance() {
                var select = document.getElementById('servers');
                if (select && !select.hasAttribute('aria-label')) {
                  select.setAttribute('aria-label', 'Servidor');
                }
                var themes = document.querySelectorAll('.dark-mode-toggle button');
                for (var i = 0; i < themes.length; i++) {
                  if (!hasName(themes[i])) {
                    themes[i].setAttribute('aria-label', 'Alternar tema');
                  }
                }
                var logo = document.querySelector('.topbar a.link');
                if (logo) {
                  if (!logo.getAttribute('href')) {
                    logo.setAttribute('href', '/');
                  }
                  if (!logo.hasAttribute('aria-label')) {
                    logo.setAttribute('aria-label', 'Pagina inicial da API');
                  }
                }
                var main = document.querySelector('.swagger-ui');
                if (main) {
                  if (main.getAttribute('role') !== 'main') {
                    main.setAttribute('role', 'main');
                  }
                  if (!main.id) {
                    main.id = 'lg-main';
                  }
                  if (!main.hasAttribute('tabindex')) {
                    main.setAttribute('tabindex', '-1');
                  }
                  if (!document.querySelector('.swagger-ui__skip-link')) {
                    var skip = document.createElement('a');
                    skip.className = 'swagger-ui__skip-link';
                    skip.href = '#lg-main';
                    skip.textContent = 'Pular para o conteudo';
                    document.body.insertBefore(skip, document.body.firstChild);
                  }
                }
                var firstTag = document.querySelector('.opblock-tag-section');
                if (firstTag && !document.getElementById('lg-operations-heading')) {
                  var heading = document.createElement('h2');
                  heading.id = 'lg-operations-heading';
                  heading.className = 'lg-visually-hidden';
                  heading.textContent = 'Operacoes da API';
                  firstTag.parentNode.insertBefore(heading, firstTag);
                }
                return Boolean(firstTag && select && main && themes.length &&
                  document.querySelector('.swagger-ui__skip-link') &&
                  !document.querySelector('.dark-mode-toggle button:not([aria-label])') &&
                  !document.querySelector('.topbar a.link:not([href])'));
              }
              function run() {
                if (enhance() && observer) {
                  observer.disconnect();
                }
              }
              var observer = window.MutationObserver ? new MutationObserver(run) : null;
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', run);
              } else {
                run();
              }
              window.addEventListener('load', run);
              if (observer) {
                observer.observe(document.documentElement, { childList: true, subtree: true });
              }
            })();
            </script>
            <link rel="stylesheet" type="text/css" href="/swagger-custom.css">
            """;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean swaggerIndex = uri.endsWith("/swagger-ui.html") || uri.endsWith("/swagger-ui/index.html");
        if (!swaggerIndex) {
            filterChain.doFilter(request, response);
            return;
        }

        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);

        byte[] body = wrapper.getContent();
        String text = new String(body, StandardCharsets.UTF_8);

        if (text.contains("</head>") && !text.contains("swagger-custom.css")) {
            text = text.replace(SWAGGER_TITLE, "<title>" + DOCUMENT_TITLE + "</title>");
            text = text.replace("</head>", HEAD_INJECTION + "</head>");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            response.setContentType("text/html;charset=UTF-8");
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } else {
            String contentType = wrapper.getContentType();
            if (contentType != null) {
                response.setContentType(contentType);
            }
            response.getOutputStream().write(body);
            response.getOutputStream().flush();
        }
    }

    private static final class BufferingResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream outputStream;
        private PrintWriter writer;

        BufferingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override
                    public void write(int b) {
                        buffer.write(b);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(jakarta.servlet.WriteListener listener) {
                        // resposta bloqueante: nao ha listener assincrono
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(buffer, getCharacterEncoding() == null
                        ? StandardCharsets.UTF_8
                        : java.nio.charset.Charset.forName(getCharacterEncoding())));
            }
            return writer;
        }

        @Override
        public void flushBuffer() {
            // buffer em memoria: nada a descarregar
        }

        byte[] getContent() {
            if (writer != null) {
                writer.flush();
            }
            return buffer.toByteArray();
        }
    }
}
