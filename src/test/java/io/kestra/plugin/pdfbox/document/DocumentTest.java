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
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class DocumentTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storage;

    @Test
    void shouldMergePdfsInOrder() throws Exception {
        URI first = Fixtures.put(storage, Fixtures.textPdf("one", "two"), ".pdf");
        URI second = Fixtures.put(storage, Fixtures.textPdf("three"), ".pdf");
        RunContext runContext = runContext();

        Merge.Output output = Merge.builder()
            .files(Property.ofValue(List.of(first.toString(), second.toString())))
            .build()
            .run(runContext);

        assertThat(output.getPageCount(), is(3));
        try (PDDocument merged = Fixtures.read(runContext, output.getUri())) {
            String text = new PDFTextStripper().getText(merged);
            assertThat(text.indexOf("one"), lessThan(text.indexOf("three")));
        }
    }

    @Test
    void shouldRefuseToMergeASinglePdf() throws Exception {
        URI only = Fixtures.put(storage, Fixtures.textPdf("one"), ".pdf");

        Merge task = Merge.builder().files(Property.ofValue(List.of(only.toString()))).build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
    }

    @Test
    void shouldSplitEveryNPagesWithARemainder() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("1", "2", "3", "4", "5"), ".pdf");
        RunContext runContext = runContext();

        Split.Output output = Split.builder()
            .from(Property.ofValue(pdf.toString()))
            .everyNPages(Property.ofValue(2))
            .build()
            .run(runContext);

        assertThat(output.getCount(), is(3));
        try (PDDocument last = Fixtures.read(runContext, output.getUris().get(2))) {
            assertThat(last.getNumberOfPages(), is(1));
        }
    }

    @Test
    void shouldSplitByExplicitRanges() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("cover", "body", "appendix a", "appendix b"), ".pdf");
        RunContext runContext = runContext();

        Split.Output output = Split.builder()
            .from(Property.ofValue(pdf.toString()))
            .ranges(Property.ofValue(List.of("1", "3-4")))
            .build()
            .run(runContext);

        assertThat(output.getCount(), is(2));
        try (PDDocument appendix = Fixtures.read(runContext, output.getUris().get(1))) {
            assertThat(appendix.getNumberOfPages(), is(2));
            assertThat(new PDFTextStripper().getText(appendix), containsString("appendix a"));
        }
    }

    @Test
    void shouldRejectARangeOutsideTheDocument() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("1", "2"), ".pdf");

        Split task = Split.builder().from(Property.ofValue(pdf.toString())).ranges(Property.ofValue(List.of("2-5"))).build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(error.getMessage(), containsString("2 page(s)"));
    }

    @Test
    void shouldRequireExactlyOneSplitMode() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("1"), ".pdf");

        Split neither = Split.builder().from(Property.ofValue(pdf.toString())).build();
        Split both = Split.builder()
            .from(Property.ofValue(pdf.toString()))
            .everyNPages(Property.ofValue(1))
            .ranges(Property.ofValue(List.of("1")))
            .build();

        assertThrows(IllegalArgumentException.class, () -> neither.run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> both.run(runContext()));
    }

    @Test
    void shouldBuildOnePagePerImageAndFitItInsideTheMargins() throws Exception {
        URI wide = Fixtures.put(storage, Fixtures.png(400, 100), ".png");
        URI tall = Fixtures.put(storage, Fixtures.png(100, 400), ".png");
        RunContext runContext = runContext();

        FromImages.Output output = FromImages.builder()
            .images(Property.ofValue(List.of(wide.toString(), tall.toString())))
            .pageSize(Property.ofValue(FromImages.PageSize.A4))
            .margin(Property.ofValue(36f))
            .build()
            .run(runContext);

        assertThat(output.getPageCount(), is(2));
        try (PDDocument pdf = Fixtures.read(runContext, output.getUri())) {
            assertThat(pdf.getPage(0).getMediaBox().getWidth(), is(PDRectangle.A4.getWidth()));
        }
    }

    @Test
    void shouldSizePagesToTheImage() throws Exception {
        URI image = Fixtures.put(storage, Fixtures.png(300, 200), ".png");
        RunContext runContext = runContext();

        FromImages.Output output = FromImages.builder()
            .images(Property.ofValue(List.of(image.toString())))
            .pageSize(Property.ofValue(FromImages.PageSize.IMAGE))
            .build()
            .run(runContext);

        try (PDDocument pdf = Fixtures.read(runContext, output.getUri())) {
            assertThat(pdf.getPage(0).getMediaBox().getWidth(), is(300f));
            assertThat(pdf.getPage(0).getMediaBox().getHeight(), is(200f));
        }
    }

    @Test
    void shouldRejectAFileThatIsNotAnImage() throws Exception {
        URI notImage = Fixtures.put(storage, "plain text".getBytes(), ".txt");

        FromImages task = FromImages.builder().images(Property.ofValue(List.of(notImage.toString()))).build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of());
    }
}
