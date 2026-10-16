package io.kestra.plugin.pdfbox.document;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pdfbox.AbstractPdfboxTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.net.URI;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Merge several PDFs into one",
    description = "Appends the pages of each input PDF, in the order given, into a single PDF stored in internal storage. Inputs must not be encrypted; decrypt them first with `security.Decrypt`."
)
@Plugin(
    examples = {
        @Example(
            title = "Merge two PDFs and password protect the result",
            full = true,
            code = """
                id: pdf_merge_encrypt
                namespace: company.team

                inputs:
                  - id: first
                    type: FILE
                  - id: second
                    type: FILE

                tasks:
                  - id: merge
                    type: io.kestra.plugin.pdfbox.document.Merge
                    files:
                      - "{{ inputs.first }}"
                      - "{{ inputs.second }}"

                  - id: encrypt
                    type: io.kestra.plugin.pdfbox.security.Encrypt
                    from: "{{ outputs.merge.uri }}"
                    userPassword: "{{ secret('PDF_USER_PASSWORD') }}"
                    ownerPassword: "{{ secret('PDF_OWNER_PASSWORD') }}"
                """
        )
    }
)
public class Merge extends AbstractPdfboxTask implements RunnableTask<Merge.Output> {
    static final int MAX_FILES = 10_000;
    static final int MAX_PAGES = 100_000;

    @Schema(title = "The PDFs to merge, as internal storage URIs, in page order")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "main")
    private Property<List<String>> files;

    @Schema(title = "Maximum number of PDFs to merge", description = "Default 100, between 2 and 10,000.")
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(2) @Max(MAX_FILES) Integer> maxFiles = Property.ofValue(100);

    @Schema(
        title = "Maximum number of pages in the merged PDF",
        description = "Checked from each input before merging, so the task fails early instead of building a huge document. Default 10,000, between 1 and 100,000."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_PAGES) Integer> maxPages = Property.ofValue(10000);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFiles = runContext.render(files).asList(String.class);
        if (rFiles.size() < 2) {
            throw new IllegalArgumentException("At least two PDFs are required to merge, got %d.".formatted(rFiles.size()));
        }

        int rMaxFiles = boundedInt(runContext, maxFiles, 100, MAX_FILES, "maxFiles");
        if (rFiles.size() > rMaxFiles) {
            throw new IllegalArgumentException(
                "%d PDFs is more than `maxFiles` (%d); merge in batches or raise the limit.".formatted(rFiles.size(), rMaxFiles)
            );
        }
        int rMaxPages = boundedInt(runContext, maxPages, 10000, MAX_PAGES, "maxPages");

        var merger = new PDFMergerUtility();
        int totalPages = 0;
        for (var uri : rFiles) {
            var file = download(runContext, uri, ".pdf").toFile();
            // Validate each input up front so a corrupt or encrypted file is named in the error.
            try (PDDocument input = load(file, "", uri)) {
                if (input.isEncrypted()) {
                    throw new IllegalArgumentException(
                        "'%s' is encrypted; remove the protection with `security.Decrypt` before merging.".formatted(uri)
                    );
                }
                totalPages += input.getNumberOfPages();
                checkPageLimit(totalPages, rMaxPages, "The merged PDF would have");
                merger.addSource(file);
            }
        }

        var target = runContext.workingDir().createTempFile(".pdf");
        merger.setDestinationFileName(target.toString());
        merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());

        int pageCount;
        try (PDDocument merged = load(target.toFile(), "", "merged document")) {
            pageCount = merged.getNumberOfPages();
        }
        var uri = runContext.storage().putFile(target.toFile());
        runContext.logger().info("Merged {} PDF(s) into {} page(s).", rFiles.size(), pageCount);
        return Output.builder().uri(uri).pageCount(pageCount).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the merged PDF")
        private final URI uri;

        @Schema(title = "Number of pages in the merged PDF")
        private final Integer pageCount;
    }
}
