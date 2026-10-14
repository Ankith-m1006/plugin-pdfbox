package io.kestra.plugin.pdfbox.metadata;

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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.net.URI;
import java.util.Calendar;
import java.util.Map;
import java.util.Set;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Set the metadata of a PDF",
    description = "Writes the given document information fields and custom keys, leaves the others unchanged, sets the modification date to now and stores the updated PDF in internal storage. Encrypted PDFs must be decrypted first with `security.Decrypt`."
)
@Plugin(
    examples = {
        @Example(
            title = "Stamp a generated report with a title, an author and a custom key",
            full = true,
            code = """
                id: pdf_update_metadata
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: metadata
                    type: io.kestra.plugin.pdfbox.metadata.Update
                    from: "{{ inputs.file }}"
                    title: "Monthly report"
                    author: "Finance team"
                    keywords: "report, finance"
                    custom:
                      executionId: "{{ execution.id }}"
                """
        )
    }
)
public class Update extends AbstractPdfboxTask implements RunnableTask<Update.Output> {
    static final Set<String> STANDARD_KEYS = Set.of(
        "Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped"
    );

    @Schema(title = "The PDF to update, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(title = "New document title")
    private Property<String> title;

    @Schema(title = "New document author")
    private Property<String> author;

    @Schema(title = "New document subject")
    private Property<String> subject;

    @Schema(title = "New document keywords")
    private Property<String> keywords;

    @Schema(title = "New creator application")
    private Property<String> creator;

    @Schema(title = "Custom document information keys to set")
    private Property<Map<String, String>> custom;

    @Override
    public Output run(RunContext runContext) throws Exception {
        try (PDDocument document = load(runContext, from, Property.ofValue(""))) {
            PDDocumentInformation info = document.getDocumentInformation();
            runContext.render(title).as(String.class).ifPresent(info::setTitle);
            runContext.render(author).as(String.class).ifPresent(info::setAuthor);
            runContext.render(subject).as(String.class).ifPresent(info::setSubject);
            runContext.render(keywords).as(String.class).ifPresent(info::setKeywords);
            runContext.render(creator).as(String.class).ifPresent(info::setCreator);
            Map<String, String> customValues = runContext.render(custom).asMap(String.class, String.class);
            for (Map.Entry<String, String> entry : customValues.entrySet()) {
                if (STANDARD_KEYS.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                        "'%s' is a standard field; set it with its own property instead of `custom`.".formatted(entry.getKey())
                    );
                }
                info.setCustomMetadataValue(entry.getKey(), entry.getValue());
            }
            info.setModificationDate(Calendar.getInstance());
            return Output.builder().uri(save(runContext, document)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the updated PDF")
        private final URI uri;
    }
}
