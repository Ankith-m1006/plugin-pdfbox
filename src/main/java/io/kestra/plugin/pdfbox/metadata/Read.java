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

import java.time.Instant;
import java.util.Calendar;
import java.util.Map;
import java.util.TreeMap;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Read the metadata of a PDF",
    description = "Returns the document information (title, author, subject, keywords, creator, producer, dates), any custom keys, the page count, the PDF version and whether the file is encrypted."
)
@Plugin(
    examples = {
        @Example(
            title = "Log the title and page count of an uploaded PDF",
            full = true,
            code = """
                id: pdf_read_metadata
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: metadata
                    type: io.kestra.plugin.pdfbox.metadata.Read
                    from: "{{ inputs.file }}"

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.metadata.title }} has {{ outputs.metadata.pageCount }} pages"
                """
        )
    }
)
public class Read extends AbstractPdfboxTask implements RunnableTask<Read.Output> {
    @Schema(title = "The PDF to read, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "main")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Override
    public Output run(RunContext runContext) throws Exception {
        try (PDDocument document = load(runContext, from, password)) {
            var info = document.getDocumentInformation();
            var custom = new TreeMap<String, String>();
            for (var key : info.getMetadataKeys()) {
                if (!Update.STANDARD_KEYS.contains(key)) {
                    var value = info.getCustomMetadataValue(key);
                    if (value != null) {
                        custom.put(key, value);
                    }
                }
            }
            return Output.builder()
                .title(info.getTitle())
                .author(info.getAuthor())
                .subject(info.getSubject())
                .keywords(info.getKeywords())
                .creator(info.getCreator())
                .producer(info.getProducer())
                .creationDate(toInstant(info.getCreationDate()))
                .modificationDate(toInstant(info.getModificationDate()))
                .custom(custom)
                .pageCount(document.getNumberOfPages())
                .pdfVersion(document.getVersion())
                .encrypted(document.isEncrypted())
                .build();
        }
    }

    private static Instant toInstant(Calendar calendar) {
        return calendar == null ? null : calendar.toInstant();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Document title")
        private final String title;

        @Schema(title = "Document author")
        private final String author;

        @Schema(title = "Document subject")
        private final String subject;

        @Schema(title = "Document keywords")
        private final String keywords;

        @Schema(title = "Application that created the original document")
        private final String creator;

        @Schema(title = "Application that produced the PDF")
        private final String producer;

        @Schema(title = "Creation date")
        private final Instant creationDate;

        @Schema(title = "Last modification date")
        private final Instant modificationDate;

        @Schema(title = "Custom document information keys and their values")
        private final Map<String, String> custom;

        @Schema(title = "Number of pages")
        private final Integer pageCount;

        @Schema(title = "PDF version, for example 1.7")
        private final Float pdfVersion;

        @Schema(title = "Whether the PDF is encrypted")
        private final Boolean encrypted;
    }
}
