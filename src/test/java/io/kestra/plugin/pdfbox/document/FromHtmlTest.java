package io.kestra.plugin.pdfbox.document;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.plugin.pdfbox.Fixtures;
import jakarta.inject.Inject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class FromHtmlTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storage;

    @Test
    void shouldRenderInlineHtml() throws Exception {
        RunContext runContext = runContext();

        FromHtml.Output output = FromHtml.builder()
            .html(Property.ofValue("<html><body><h1>Daily report</h1><p>All good</p></body></html>"))
            .pageSize(Property.ofValue(FromImages.PageSize.LETTER))
            .build()
            .run(runContext);

        assertThat(output.getPageCount(), is(1));
        try (PDDocument pdf = Fixtures.read(runContext, output.getUri())) {
            assertThat(new PDFTextStripper().getText(pdf), containsString("Daily report"));
            assertThat((double) pdf.getPage(0).getMediaBox().getWidth(), closeTo(PDRectangle.LETTER.getWidth(), 1.0));
        }
    }

    @Test
    void shouldRenderAnHtmlFileFromStorageWithAnImageFromStorage() throws Exception {
        URI logo = Fixtures.put(storage, Fixtures.png(50, 50), ".png");
        URI page = Fixtures.put(storage, ("<html><body><img src=\"" + logo + "\"/><p>Invoice</p></body></html>").getBytes(), ".html");
        RunContext runContext = runContext();

        FromHtml.Output output = FromHtml.builder().from(Property.ofValue(page.toString())).build().run(runContext);

        try (PDDocument pdf = Fixtures.read(runContext, output.getUri())) {
            assertThat(new PDFTextStripper().getText(pdf), containsString("Invoice"));
            assertThat(pdf.getPage(0).getResources().getXObjectNames().iterator().hasNext(), is(true));
        }
    }

    @Test
    void shouldFailClearlyOnMalformedXhtml() {
        FromHtml task = FromHtml.builder().html(Property.ofValue("<html><body><p>unclosed & broken</body>")).build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(error.getMessage(), containsString("well-formed XHTML"));
    }

    @Test
    void shouldRequireExactlyOneSource() {
        FromHtml none = FromHtml.builder().build();

        assertThrows(IllegalArgumentException.class, () -> none.run(runContext()));
    }

    @Test
    void shouldOnlyAllowInlineAndStorageResourcesByDefault() {
        assertThat(FromHtml.isAllowed("data:image/png;base64,AAAA", false), is(true));
        assertThat(FromHtml.isAllowed("kestra:///main/logo.png", false), is(true));
        assertThat(FromHtml.isAllowed("https://example.com/logo.png", false), is(false));
        assertThat(FromHtml.isAllowed("http://169.254.169.254/latest/meta-data", false), is(false));
        assertThat(FromHtml.isAllowed("file:///etc/passwd", false), is(false));
        assertThat(FromHtml.isAllowed("file:///etc/passwd", true), is(false));
        assertThat(FromHtml.isAllowed("https://example.com/logo.png", true), is(true));
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of());
    }
}
