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
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Password protect a PDF",
    description = "Encrypts a PDF with AES-256 (or AES-128). The user password is needed to open the file; the owner password unlocks every permission. Permissions such as printing or copying are enforced by PDF readers, not by the encryption itself."
)
@Plugin(
    examples = {
        @Example(
            title = "Protect a PDF and only allow printing",
            full = true,
            code = """
                id: pdf_encrypt
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: encrypt
                    type: io.kestra.plugin.pdfbox.security.Encrypt
                    from: "{{ inputs.file }}"
                    userPassword: "{{ secret('PDF_USER_PASSWORD') }}"
                    ownerPassword: "{{ secret('PDF_OWNER_PASSWORD') }}"
                    canPrint: true
                    canCopy: false
                    canModify: false
                """
        )
    }
)
public class Encrypt extends AbstractPdfboxTask implements RunnableTask<Encrypt.Output> {
    @Schema(title = "The PDF to encrypt, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(
        title = "Password required to open the PDF",
        description = "Leave empty to let anyone open the file while still restricting permissions."
    )
    @PluginProperty(secret = true, group = "main")
    @ToString.Exclude
    private Property<String> userPassword;

    @Schema(title = "Password that unlocks every permission")
    @NotNull
    @PluginProperty(secret = true, group = "main")
    @ToString.Exclude
    private Property<String> ownerPassword;

    @Schema(title = "Encryption key length in bits")
    @Builder.Default
    private Property<KeyLength> keyLength = Property.ofValue(KeyLength.AES_256);

    @Schema(title = "Allow printing")
    @Builder.Default
    private Property<Boolean> canPrint = Property.ofValue(true);

    @Schema(title = "Allow modifying the content")
    @Builder.Default
    private Property<Boolean> canModify = Property.ofValue(false);

    @Schema(title = "Allow copying or extracting text and images")
    @Builder.Default
    private Property<Boolean> canCopy = Property.ofValue(true);

    @Schema(title = "Allow adding or changing annotations")
    @Builder.Default
    private Property<Boolean> canAnnotate = Property.ofValue(false);

    @Schema(title = "Allow filling in form fields")
    @Builder.Default
    private Property<Boolean> canFillForms = Property.ofValue(true);

    @Schema(title = "Allow inserting, rotating or deleting pages")
    @Builder.Default
    private Property<Boolean> canAssemble = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String owner = required(runContext, ownerPassword, "ownerPassword");
        String user = runContext.render(userPassword).as(String.class).orElse("");

        AccessPermission permission = new AccessPermission();
        permission.setCanPrint(runContext.render(canPrint).as(Boolean.class).orElse(true));
        permission.setCanModify(runContext.render(canModify).as(Boolean.class).orElse(false));
        permission.setCanExtractContent(runContext.render(canCopy).as(Boolean.class).orElse(true));
        permission.setCanModifyAnnotations(runContext.render(canAnnotate).as(Boolean.class).orElse(false));
        permission.setCanFillInForm(runContext.render(canFillForms).as(Boolean.class).orElse(true));
        permission.setCanAssembleDocument(runContext.render(canAssemble).as(Boolean.class).orElse(false));

        StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, permission);
        policy.setEncryptionKeyLength(runContext.render(keyLength).as(KeyLength.class).orElse(KeyLength.AES_256).bits);

        try (PDDocument document = load(runContext, from, Property.ofValue(""))) {
            document.protect(policy);
            return Output.builder().uri(save(runContext, document)).build();
        }
    }

    public enum KeyLength {
        AES_128(128),
        AES_256(256);

        private final int bits;

        KeyLength(int bits) {
            this.bits = bits;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the encrypted PDF")
        private final URI uri;
    }
}
