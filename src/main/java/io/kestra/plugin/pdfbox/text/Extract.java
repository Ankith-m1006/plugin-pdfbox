package io.kestra.plugin.pdfbox.text;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pdfbox.AbstractPdfboxTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Extract text from a PDF",
    description = "Reads the text layer of a PDF from internal storage, for the whole document or a page range. Scanned PDFs without a text layer return empty text; render their pages with `image.Render` and run OCR instead."
)
@Plugin(
    examples = {
        @Example(
            title = "Extract the text of the first five pages of an uploaded PDF",
            full = true,
            code = """
                id: pdf_extract_text
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: extract
                    type: io.kestra.plugin.pdfbox.text.Extract
                    from: "{{ inputs.file }}"
                    startPage: 1
                    endPage: 5

                  - id: log_text
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.extract.text }}"
                """
        ),
        @Example(
            title = "Extract text page by page from an encrypted PDF and store it in internal storage",
            full = true,
            code = """
                id: pdf_extract_pages
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: extract
                    type: io.kestra.plugin.pdfbox.text.Extract
                    from: "{{ inputs.file }}"
                    password: "{{ secret('PDF_PASSWORD') }}"
                    perPage: true
                    fetchType: STORE
                """
        )
    }
)
public class Extract extends AbstractPdfboxTask implements RunnableTask<Extract.Output> {
    static final int MAX_PAGES = 100_000;

    @Schema(title = "The PDF to read, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "main")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "First page to read, starting at 1", description = "Default 1.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) Integer> startPage = Property.ofValue(1);

    @Schema(title = "Last page to read, inclusive", description = "Defaults to the last page.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> endPage;

    @Schema(
        title = "Also return the text of each page separately",
        description = "When true, `pages` (or `pageUris` with `fetchType: STORE`) holds one entry per page in the range, in order. Default `false`."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> perPage = Property.ofValue(false);

    @Schema(
        title = "Sort text by its position on the page",
        description = "Helps with multi-column layouts and PDFs whose text is written out of reading order. Default `false`."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> sortByPosition = Property.ofValue(false);

    @Schema(
        title = "How to return the text",
        description = "`FETCH` (default) returns it in `text` and `pages`. `STORE` writes UTF-8 text files to internal storage and returns `uri` and `pageUris`; use it for long documents, since task outputs are limited in size. `NONE` returns only `pageCount`. `FETCH_ONE` is not supported."
    )
    @PluginProperty(group = "destination")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(
        title = "Maximum number of pages to read",
        description = "The task fails instead of reading more pages than this; narrow the page range for longer documents. Default 1,000, between 1 and 100,000."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_PAGES) Integer> maxPages = Property.ofValue(1000);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        if (rFetchType == FetchType.FETCH_ONE) {
            throw new IllegalArgumentException("`fetchType: FETCH_ONE` is not supported; use `FETCH`, `STORE` or `NONE`.");
        }
        int rMaxPages = boundedInt(runContext, maxPages, 1000, MAX_PAGES, "maxPages");
        boolean rSortByPosition = runContext.render(sortByPosition).as(Boolean.class).orElse(false);
        boolean rPerPage = runContext.render(perPage).as(Boolean.class).orElse(false);

        try (PDDocument document = load(runContext, from, password)) {
            int pageCount = document.getNumberOfPages();
            var range = pageRange(runContext, startPage, endPage, pageCount);
            checkPageLimit(range[1] - range[0] + 1, rMaxPages, "Reading");
            if (rFetchType == FetchType.NONE) {
                return Output.builder().pageCount(pageCount).build();
            }

            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(rSortByPosition);
            stripper.setStartPage(range[0]);
            stripper.setEndPage(range[1]);
            var text = stripper.getText(document);

            List<String> pages = null;
            if (rPerPage) {
                pages = new ArrayList<>();
                for (int page = range[0]; page <= range[1]; page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    pages.add(stripper.getText(document));
                }
            }

            runContext.logger().debug("Extracted {} characters from pages {}-{} of {}.", text.length(), range[0], range[1], pageCount);
            if (rFetchType == FetchType.STORE) {
                List<URI> pageUris = null;
                if (pages != null) {
                    pageUris = new ArrayList<>();
                    for (var page : pages) {
                        pageUris.add(storeText(runContext, page));
                    }
                }
                return Output.builder().uri(storeText(runContext, text)).pageUris(pageUris).pageCount(pageCount).build();
            }
            return Output.builder().text(text).pages(pages).pageCount(pageCount).build();
        }
    }

    private static URI storeText(RunContext runContext, String text) throws Exception {
        var file = runContext.workingDir().createTempFile(".txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return runContext.storage().putFile(file.toFile());
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Text of the selected pages, with `fetchType: FETCH`")
        private final String text;

        @Schema(title = "Internal storage URI of the text file, with `fetchType: STORE`")
        private final URI uri;

        @Schema(title = "Text of each selected page, when `perPage` is true and `fetchType` is `FETCH`")
        private final List<String> pages;

        @Schema(title = "Internal storage URIs of each page's text file, when `perPage` is true and `fetchType` is `STORE`")
        private final List<URI> pageUris;

        @Schema(title = "Total number of pages in the document")
        private final Integer pageCount;
    }
}
