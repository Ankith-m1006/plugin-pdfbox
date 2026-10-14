package io.kestra.plugin.pdfbox.document;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pdfbox.AbstractPdfboxTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
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
    @Schema(title = "The PDFs to merge, as internal storage URIs, in page order")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<List<String>> files;

    @Override
    public Output run(RunContext runContext) throws Exception {
        List<String> uris = runContext.render(files).asList(String.class);
        if (uris.size() < 2) {
            throw new IllegalArgumentException("At least two PDFs are required to merge, got %d.".formatted(uris.size()));
        }

        PDFMergerUtility merger = new PDFMergerUtility();
        for (String uri : uris) {
            File file = download(runContext, uri, ".pdf").toFile();
            // Validate each input up front so a corrupt or encrypted file is named in the error.
            try (PDDocument ignored = load(file, "", uri)) {
                merger.addSource(file);
            }
        }

        Path target = runContext.workingDir().createTempFile(".pdf");
        merger.setDestinationFileName(target.toString());
        merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());

        int pageCount;
        try (PDDocument merged = load(target.toFile(), "", "merged document")) {
            pageCount = merged.getNumberOfPages();
        }
        URI uri = runContext.storage().putFile(target.toFile());
        runContext.logger().info("Merged {} PDFs into {} pages.", uris.size(), pageCount);
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
