package io.kestra.plugin.pdfbox.text;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pdfbox.AbstractPdfboxTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

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
            title = "Extract text page by page from an encrypted PDF",
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
                """
        )
    }
)
public class Extract extends AbstractPdfboxTask implements RunnableTask<Extract.Output> {
    @Schema(title = "The PDF to read, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "First page to read, starting at 1")
    @Builder.Default
    private Property<@Min(1) Integer> startPage = Property.ofValue(1);

    @Schema(title = "Last page to read, inclusive. Defaults to the last page")
    private Property<@Min(1) Integer> endPage;

    @Schema(
        title = "Also return the text of each page separately",
        description = "When true, `pages` holds one entry per page in the range, in order."
    )
    @Builder.Default
    private Property<Boolean> perPage = Property.ofValue(false);

    @Schema(
        title = "Sort text by its position on the page",
        description = "Helps with multi-column layouts and PDFs whose text is written out of reading order."
    )
    @Builder.Default
    private Property<Boolean> sortByPosition = Property.ofValue(false);

    @Schema(
        title = "Write the text to internal storage instead of the task output",
        description = "Task outputs are limited in size, so use this for long documents: `uri` then points to a UTF-8 text file and `text` is empty. With `perPage`, each page is also written to its own file, listed in `pageUris`."
    )
    @Builder.Default
    private Property<Boolean> store = Property.ofValue(false);

    @Schema(
        title = "Maximum number of pages to read",
        description = "The task fails instead of reading more pages than this; narrow the page range for longer documents."
    )
    @Builder.Default
    private Property<@Min(1) Integer> maxPages = Property.ofValue(1000);

    @Override
    public Output run(RunContext runContext) throws Exception {
        try (PDDocument document = load(runContext, from, password)) {
            int pageCount = document.getNumberOfPages();
            int[] range = pageRange(runContext, startPage, endPage, pageCount);
            checkPageLimit(range[1] - range[0] + 1, positive(runContext, maxPages, 1000, "maxPages"), "Reading");

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(runContext.render(sortByPosition).as(Boolean.class).orElse(false));
            stripper.setStartPage(range[0]);
            stripper.setEndPage(range[1]);
            String text = stripper.getText(document);

            List<String> pages = null;
            if (runContext.render(perPage).as(Boolean.class).orElse(false)) {
                pages = new ArrayList<>();
                for (int page = range[0]; page <= range[1]; page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    pages.add(stripper.getText(document));
                }
            }

            runContext.logger().debug("Extracted {} characters from pages {}-{} of {}.", text.length(), range[0], range[1], pageCount);
            if (runContext.render(store).as(Boolean.class).orElse(false)) {
                List<URI> pageUris = null;
                if (pages != null) {
                    pageUris = new ArrayList<>();
                    for (String page : pages) {
                        pageUris.add(storeText(runContext, page));
                    }
                }
                return Output.builder().uri(storeText(runContext, text)).pageUris(pageUris).pageCount(pageCount).build();
            }
            return Output.builder().text(text).pages(pages).pageCount(pageCount).build();
        }
    }

    private static URI storeText(RunContext runContext, String text) throws Exception {
        Path file = runContext.workingDir().createTempFile(".txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return runContext.storage().putFile(file.toFile());
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Text of the selected pages")
        private final String text;

        @Schema(title = "Internal storage URI of the text file, when `store` is true")
        private final URI uri;

        @Schema(title = "Text of each selected page, when `perPage` is true and `store` is false")
        private final List<String> pages;

        @Schema(title = "Internal storage URIs of each page's text file, when `perPage` and `store` are true")
        private final List<URI> pageUris;

        @Schema(title = "Total number of pages in the document")
        private final Integer pageCount;
    }
}
