package io.kestra.plugin.pdfbox.document;

import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pdfbox.AbstractPdfboxTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.slf4j.Logger;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Render HTML to a PDF",
    description = """
        Converts an XHTML document (inline `html` or a file in internal storage via `from`) to a PDF with OpenHTMLtoPDF. \
        The input must be well-formed XHTML: close every tag and escape `&` as `&amp;`. Page margins and breaks come from CSS, \
        for example `@page { margin: 20mm }`.

        Images, stylesheets and fonts are loaded only from inline `data:` URIs and Kestra internal storage (`kestra://` URIs, \
        or paths relative to `baseUri` when it points to internal storage). Remote `http(s)` resources are blocked by default \
        because the HTML may come from user input; set `allowRemoteResources` to fetch them through Kestra's HTTP client. \
        `file:` URIs are always blocked. A resource that cannot be loaded is skipped with a warning naming its URL, and \
        the `from` file and every resource are limited to `maxResourceBytes`."""
)
@Plugin(
    examples = {
        @Example(
            title = "Generate a PDF report from HTML",
            full = true,
            code = """
                id: pdf_from_html
                namespace: company.team

                tasks:
                  - id: render
                    type: io.kestra.plugin.pdfbox.document.FromHtml
                    html: |
                      <html>
                        <head><style>@page { margin: 20mm }</style></head>
                        <body>
                          <h1>Daily report</h1>
                          <p>Execution: {{ execution.id }}</p>
                        </body>
                      </html>
                    pageSize: A4

                  - id: log_uri
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.render.uri }}"
                """
        ),
        @Example(
            title = "Render an uploaded HTML file",
            full = true,
            code = """
                id: pdf_from_html_file
                namespace: company.team

                inputs:
                  - id: page
                    type: FILE

                tasks:
                  - id: render
                    type: io.kestra.plugin.pdfbox.document.FromHtml
                    from: "{{ inputs.page }}"
                    pageSize: LETTER
                """
        ),
        @Example(
            title = "Embed an uploaded logo in generated HTML",
            full = true,
            code = """
                id: pdf_from_html_logo
                namespace: company.team

                inputs:
                  - id: logo
                    type: FILE

                tasks:
                  - id: render
                    type: io.kestra.plugin.pdfbox.document.FromHtml
                    html: |
                      <html><body>
                        <img src="{{ inputs.logo }}" width="120"/>
                        <p>Invoice</p>
                      </body></html>
                """
        )
    }
)
public class FromHtml extends AbstractPdfboxTask implements RunnableTask<FromHtml.Output> {
    @Schema(title = "XHTML to render. Set either `html` or `from`")
    private Property<String> html;

    @Schema(title = "An XHTML file to render, as an internal storage URI. Set either `html` or `from`")
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(
        title = "Base URI for relative links in the HTML",
        description = "Usually an internal storage URI; relative `src` and `href` values are resolved against it. Defaults to `from` when that is set."
    )
    private Property<String> baseUri;

    @Schema(title = "Default page size, used when the CSS does not set one")
    @Builder.Default
    private Property<FromImages.PageSize> pageSize = Property.ofValue(FromImages.PageSize.A4);

    @Schema(title = "Default page orientation")
    @Builder.Default
    private Property<FromImages.Orientation> orientation = Property.ofValue(FromImages.Orientation.PORTRAIT);

    @Schema(
        title = "Allow loading remote `http` and `https` resources",
        description = "Off by default to avoid server-side request forgery when the HTML is not fully trusted. When on, remote resources are fetched with Kestra's HTTP client."
    )
    @Builder.Default
    private Property<Boolean> allowRemoteResources = Property.ofValue(false);

    @Schema(
        title = "Largest HTML file or resource to load, in bytes",
        description = "Applies to the `from` file and to every image, stylesheet or font, from internal storage or the network."
    )
    @Min(1)
    @Builder.Default
    private Property<Long> maxResourceBytes = Property.ofValue(20L * 1024 * 1024);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String inline = runContext.render(html).as(String.class).orElse(null);
        String fromUri = runContext.render(from).as(String.class).orElse(null);
        if ((inline == null) == (fromUri == null)) {
            throw new IllegalArgumentException("Set exactly one of `html` or `from`.");
        }
        long sizeLimit = runContext.render(maxResourceBytes).as(Long.class).orElse(20L * 1024 * 1024);
        if (sizeLimit < 1) {
            throw new IllegalArgumentException("`maxResourceBytes` must be at least 1, got %d.".formatted(sizeLimit));
        }
        String content = inline;
        if (content == null) {
            try (InputStream in = runContext.storage().getFile(URI.create(fromUri))) {
                content = new String(readLimited(in, sizeLimit, fromUri), StandardCharsets.UTF_8);
            }
        }
        String base = runContext.render(baseUri).as(String.class).orElse(fromUri);
        boolean remote = runContext.render(allowRemoteResources).as(Boolean.class).orElse(false);
        Logger logger = runContext.logger();

        FromImages.PageSize size = runContext.render(pageSize).as(FromImages.PageSize.class).orElse(FromImages.PageSize.A4);
        if (size == FromImages.PageSize.IMAGE) {
            throw new IllegalArgumentException("`IMAGE` page size only applies to `document.FromImages`.");
        }
        PDRectangle page = size.rectangle(runContext.render(orientation).as(FromImages.Orientation.class).orElse(FromImages.Orientation.PORTRAIT));

        Path target = runContext.workingDir().createTempFile(".pdf");
        try (OutputStream out = Files.newOutputStream(target); HttpClient client = remote ? httpClient(runContext, sizeLimit) : null) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(content, base);
            builder.useDefaultPageSize(toMillimeters(page.getWidth()), toMillimeters(page.getHeight()), BaseRendererBuilder.PageSizeUnits.MM);
            builder.useUriResolver(FromHtml::resolve);
            builder.useExternalResourceAccessControl((uri, type) -> isAllowed(uri, remote), ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            builder.useProtocolsStreamImplementation(new StorageStreamFactory(runContext, sizeLimit, logger), "kestra");
            if (client != null) {
                builder.useHttpStreamImplementation(new KestraHttpStreamFactory(client, sizeLimit, logger));
            }
            builder.toStream(out);
            try {
                builder.run();
            } catch (Exception e) {
                if (isParseError(e)) {
                    throw new IllegalArgumentException(
                        "Unable to parse the HTML; it must be well-formed XHTML (closed tags, `&amp;` for `&`): " + rootMessage(e), e
                    );
                }
                throw new IOException("Unable to render the HTML to PDF: " + rootMessage(e), e);
            }
        }

        int pageCount;
        try (PDDocument document = load(target.toFile(), "", "rendered HTML")) {
            pageCount = document.getNumberOfPages();
        }
        URI uri = runContext.storage().putFile(target.toFile());
        return Output.builder().uri(uri).pageCount(pageCount).build();
    }

    private static HttpClient httpClient(RunContext runContext, long sizeLimit) throws Exception {
        return HttpClient.builder()
            .runContext(runContext)
            .configuration(HttpConfiguration.builder().maxContentLength((int) Math.min(sizeLimit, Integer.MAX_VALUE)).build())
            .build();
    }

    static boolean isParseError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SAXException || cause instanceof javax.xml.transform.TransformerException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : error.toString();
    }

    static boolean isAllowed(String uri, boolean remote) {
        if (uri == null) {
            return false;
        }
        String scheme = scheme(uri);
        return switch (scheme) {
            case "data", "kestra" -> true;
            case "http", "https" -> remote;
            default -> false;
        };
    }

    private static String resolve(String base, String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI target = new URI(uri.trim());
            if (target.isAbsolute() || base == null) {
                return target.toString();
            }
            return new URI(base).resolve(target).toString();
        } catch (Exception e) {
            return uri.startsWith("data:") ? uri : null;
        }
    }

    private static String scheme(String uri) {
        int colon = uri.indexOf(':');
        return colon > 0 ? uri.substring(0, colon).toLowerCase(Locale.ROOT) : "";
    }

    private static float toMillimeters(float points) {
        return points * 25.4f / 72f;
    }

    private record StorageStreamFactory(RunContext runContext, long sizeLimit, Logger logger) implements FSStreamFactory {
        @Override
        public FSStream getUrl(String url) {
            return new BytesStream(url, logger, () -> {
                try (InputStream in = runContext.storage().getFile(URI.create(url))) {
                    return readLimited(in, sizeLimit, url);
                }
            });
        }
    }

    private record KestraHttpStreamFactory(HttpClient client, long sizeLimit, Logger logger) implements FSStreamFactory {
        @Override
        public FSStream getUrl(String url) {
            return new BytesStream(url, logger, () -> {
                byte[] body = client.request(HttpRequest.of(URI.create(url)), byte[].class).getBody();
                if (body != null && body.length > sizeLimit) {
                    throw new IOException("'%s' is larger than %d bytes.".formatted(url, sizeLimit));
                }
                return body;
            });
        }
    }

    private record BytesStream(String url, Logger logger, ThrowingSupplier supplier) implements FSStream {
        @Override
        public InputStream getStream() {
            try {
                byte[] bytes = supplier.get();
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            } catch (Exception e) {
                // OpenHTMLtoPDF treats a null stream as a missing resource and keeps rendering; name the skipped resource.
                logger.warn("Skipped the resource '{}': {}", url, e.getMessage());
                return null;
            }
        }

        @Override
        public Reader getReader() {
            InputStream stream = getStream();
            return stream == null ? null : new InputStreamReader(stream, StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        byte[] get() throws Exception;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the PDF")
        private final URI uri;

        @Schema(title = "Number of pages")
        private final Integer pageCount;
    }
}
