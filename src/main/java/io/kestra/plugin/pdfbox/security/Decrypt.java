package io.kestra.plugin.pdfbox.security;

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

import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Remove the password protection of a PDF",
    description = "Opens an encrypted PDF with its password and stores an unencrypted copy in internal storage, for example before merging or filling it. Fails with a clear message when the password is missing or wrong."
)
@Plugin(
    examples = {
        @Example(
            title = "Unlock a protected PDF before extracting its text",
            full = true,
            code = """
                id: pdf_decrypt
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: decrypt
                    type: io.kestra.plugin.pdfbox.security.Decrypt
                    from: "{{ inputs.file }}"
                    password: "{{ secret('PDF_PASSWORD') }}"

                  - id: text
                    type: io.kestra.plugin.pdfbox.text.Extract
                    from: "{{ outputs.decrypt.uri }}"
                """
        )
    }
)
public class Decrypt extends AbstractPdfboxTask implements RunnableTask<Decrypt.Output> {
    @Schema(title = "The encrypted PDF, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(
        title = "Password of the PDF",
        description = "The owner password is needed to remove the protection of a file that restricts permissions without a user password."
    )
    @NotNull
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Override
    public Output run(RunContext runContext) throws Exception {
        try (PDDocument document = load(runContext, from, password)) {
            if (!document.isEncrypted()) {
                runContext.logger().warn("The PDF is not encrypted; storing it unchanged.");
            } else if (!document.getCurrentAccessPermission().isOwnerPermission()) {
                throw new IllegalArgumentException(
                    "The password opens the PDF but is not its owner password, so the protection cannot be removed."
                );
            }
            document.setAllSecurityToBeRemoved(true);
            return Output.builder().uri(save(runContext, document)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the decrypted PDF")
        private final URI uri;
    }
}
