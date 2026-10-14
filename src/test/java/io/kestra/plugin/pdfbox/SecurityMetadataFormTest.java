package io.kestra.plugin.pdfbox;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.plugin.pdfbox.form.Fill;
import io.kestra.plugin.pdfbox.metadata.Read;
import io.kestra.plugin.pdfbox.metadata.Update;
import io.kestra.plugin.pdfbox.security.Decrypt;
import io.kestra.plugin.pdfbox.security.Encrypt;
import jakarta.inject.Inject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class SecurityMetadataFormTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storage;

    @Test
    void shouldEncryptWithPasswordsAndPermissions() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("confidential"), ".pdf");
        RunContext runContext = runContext();

        Encrypt.Output output = Encrypt.builder()
            .from(Property.ofValue(pdf.toString()))
            .userPassword(Property.ofValue("user"))
            .ownerPassword(Property.ofValue("owner"))
            .canPrint(Property.ofValue(false))
            .build()
            .run(runContext);

        try (PDDocument encrypted = Fixtures.read(runContext, output.getUri(), "user")) {
            assertThat(encrypted.isEncrypted(), is(true));
            assertThat(encrypted.getCurrentAccessPermission().canPrint(), is(false));
        }
        assertThrows(Exception.class, () -> Fixtures.read(runContext, output.getUri(), "wrong").close());
    }

    @Test
    void shouldRemoveProtectionWithTheOwnerPassword() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.encrypt(Fixtures.textPdf("x"), "user", "owner"), ".pdf");
        RunContext runContext = runContext();

        Decrypt.Output output = Decrypt.builder()
            .from(Property.ofValue(pdf.toString()))
            .password(Property.ofValue("owner"))
            .build()
            .run(runContext);

        try (PDDocument decrypted = Fixtures.read(runContext, output.getUri())) {
            assertThat(decrypted.isEncrypted(), is(false));
        }
    }

    @Test
    void shouldNotRemoveProtectionWithOnlyTheUserPassword() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.encrypt(Fixtures.textPdf("x"), "user", "owner"), ".pdf");

        Decrypt task = Decrypt.builder().from(Property.ofValue(pdf.toString())).password(Property.ofValue("user")).build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(error.getMessage(), containsString("owner password"));
    }

    @Test
    void shouldUpdateThenReadMetadata() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a", "b"), ".pdf");
        RunContext runContext = runContext();

        Update.Output updated = Update.builder()
            .from(Property.ofValue(pdf.toString()))
            .title(Property.ofValue("Monthly report"))
            .author(Property.ofValue("Finance"))
            .custom(Property.ofValue(Map.of("executionId", "abc123")))
            .build()
            .run(runContext);

        Read.Output read = Read.builder().from(Property.ofValue(updated.getUri().toString())).build().run(runContext);

        assertThat(read.getTitle(), is("Monthly report"));
        assertThat(read.getAuthor(), is("Finance"));
        assertThat(read.getCustom(), hasEntry("executionId", "abc123"));
        assertThat(read.getPageCount(), is(2));
        assertThat(read.getEncrypted(), is(false));
        assertThat(read.getModificationDate(), notNullValue());
    }

    @Test
    void shouldRejectAStandardKeyInCustomMetadata() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a"), ".pdf");

        Update task = Update.builder()
            .from(Property.ofValue(pdf.toString()))
            .custom(Property.ofValue(Map.of("Title", "x")))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
    }

    @Test
    void shouldFillTextAndCheckBoxFields() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.formPdf(), ".pdf");
        RunContext runContext = runContext();

        Fill.Output output = Fill.builder()
            .from(Property.ofValue(pdf.toString()))
            .fields(Property.ofValue(Map.of("full_name", "Jane Doe", "remote", "true")))
            .build()
            .run(runContext);

        assertThat(output.getFilledFields(), is(2));
        try (PDDocument filled = Fixtures.read(runContext, output.getUri())) {
            PDAcroForm form = filled.getDocumentCatalog().getAcroForm();
            assertThat(form.getField("full_name").getValueAsString(), is("Jane Doe"));
            assertThat(((PDCheckBox) form.getField("remote")).isChecked(), is(true));
        }
    }

    @Test
    void shouldFlattenTheForm() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.formPdf(), ".pdf");
        RunContext runContext = runContext();

        Fill.Output output = Fill.builder()
            .from(Property.ofValue(pdf.toString()))
            .fields(Property.ofValue(Map.of("full_name", "Jane Doe")))
            .flatten(Property.ofValue(true))
            .build()
            .run(runContext);

        try (PDDocument filled = Fixtures.read(runContext, output.getUri())) {
            PDAcroForm form = filled.getDocumentCatalog().getAcroForm();
            assertThat(form == null || form.getFields().isEmpty(), is(true));
        }
    }

    @Test
    void shouldNameUnknownFieldsUnlessTheyAreIgnored() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.formPdf(), ".pdf");

        Fill strict = Fill.builder()
            .from(Property.ofValue(pdf.toString()))
            .fields(Property.ofValue(Map.of("nickname", "JD")))
            .build();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> strict.run(runContext()));
        assertThat(error.getMessage(), allOf(containsString("nickname"), containsString("full_name")));

        Fill.Output lenient = Fill.builder()
            .from(Property.ofValue(pdf.toString()))
            .fields(Property.ofValue(Map.of("nickname", "JD")))
            .ignoreUnknownFields(Property.ofValue(true))
            .build()
            .run(runContext());
        assertThat(lenient.getFilledFields(), is(0));
    }

    @Test
    void shouldRejectAPdfWithoutAForm() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("no form"), ".pdf");

        Fill task = Fill.builder().from(Property.ofValue(pdf.toString())).fields(Property.ofValue(Map.of("a", "b"))).build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of());
    }
}
