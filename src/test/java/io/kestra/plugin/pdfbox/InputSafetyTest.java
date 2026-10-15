package io.kestra.plugin.pdfbox;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.plugin.pdfbox.document.FromImages;
import io.kestra.plugin.pdfbox.document.Merge;
import io.kestra.plugin.pdfbox.document.Split;
import io.kestra.plugin.pdfbox.form.Fill;
import io.kestra.plugin.pdfbox.image.Render;
import io.kestra.plugin.pdfbox.metadata.Read;
import io.kestra.plugin.pdfbox.metadata.Update;
import io.kestra.plugin.pdfbox.security.Encrypt;
import io.kestra.plugin.pdfbox.text.Extract;
import jakarta.inject.Inject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Encrypted, corrupt, empty and oversized inputs across the tasks, as asked for in the review of the first version.
 */
@KestraTest
class InputSafetyTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storage;

    // --- the user password alone must not produce an unencrypted copy ---

    @Test
    void shouldRefuseToSplitWithOnlyTheUserPassword() throws Exception {
        URI pdf = encrypted(Fixtures.textPdf("a", "b"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Split.builder()
            .from(Property.ofValue(pdf.toString()))
            .password(Property.ofValue("user"))
            .everyNPages(Property.ofValue(1))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("owner password"));
    }

    @Test
    void shouldSplitWithTheOwnerPassword() throws Exception {
        URI pdf = encrypted(Fixtures.textPdf("a", "b"));
        RunContext runContext = runContext();

        Split.Output output = Split.builder()
            .from(Property.ofValue(pdf.toString()))
            .password(Property.ofValue("owner"))
            .everyNPages(Property.ofValue(1))
            .build()
            .run(runContext);

        assertThat(output.getCount(), is(2));
        try (PDDocument part = Fixtures.read(runContext, output.getUris().get(0))) {
            assertThat(part.isEncrypted(), is(false));
        }
    }

    @Test
    void shouldRefuseToFillWithOnlyTheUserPassword() throws Exception {
        URI pdf = encrypted(Fixtures.formPdf());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Fill.builder()
            .from(Property.ofValue(pdf.toString()))
            .password(Property.ofValue("user"))
            .fields(Property.ofValue(Map.of("full_name", "Jane Doe")))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("owner password"));
    }

    @Test
    void shouldRefuseToUpdateMetadataWithOnlyTheUserPassword() throws Exception {
        URI pdf = encrypted(Fixtures.textPdf("a"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Update.builder()
            .from(Property.ofValue(pdf.toString()))
            .password(Property.ofValue("user"))
            .title(Property.ofValue("New title"))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("owner password"));
    }

    @Test
    void shouldUpdateTheMetadataOfAnEncryptedPdfWithTheOwnerPassword() throws Exception {
        URI pdf = encrypted(Fixtures.textPdf("a"));
        RunContext runContext = runContext();

        Update.Output output = Update.builder()
            .from(Property.ofValue(pdf.toString()))
            .password(Property.ofValue("owner"))
            .title(Property.ofValue("New title"))
            .build()
            .run(runContext);

        try (PDDocument updated = Fixtures.read(runContext, output.getUri())) {
            assertThat(updated.isEncrypted(), is(false));
            assertThat(updated.getDocumentInformation().getTitle(), is("New title"));
        }
    }

    @Test
    void shouldRefuseToEncryptAnAlreadyEncryptedPdf() throws Exception {
        // An empty user password opens the file, which must not let a new owner password replace the old one.
        URI pdf = Fixtures.put(storage, Fixtures.encrypt(Fixtures.textPdf("a"), "", "owner"), ".pdf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Encrypt.builder()
            .from(Property.ofValue(pdf.toString()))
            .ownerPassword(Property.ofValue("attacker"))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("already encrypted"));
    }

    @Test
    void shouldRefuseToMergeAnEncryptedPdf() throws Exception {
        URI plain = Fixtures.put(storage, Fixtures.textPdf("a"), ".pdf");
        URI locked = Fixtures.put(storage, Fixtures.encrypt(Fixtures.textPdf("b"), "", "owner"), ".pdf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Merge.builder()
            .files(Property.ofValue(List.of(plain.toString(), locked.toString())))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("encrypted"));
    }

    // --- wrong passwords and corrupt files on more than text.Extract ---

    @Test
    void shouldFailClearlyOnAWrongPasswordInEveryReadingTask() throws Exception {
        String pdf = encrypted(Fixtures.formPdf()).toString();
        Property<String> wrong = Property.ofValue("nope");

        List<Executable> tasks = List.of(
            () -> Render.builder().from(Property.ofValue(pdf)).password(wrong).build().run(runContext()),
            () -> io.kestra.plugin.pdfbox.image.Extract.builder().from(Property.ofValue(pdf)).password(wrong).build().run(runContext()),
            () -> Split.builder().from(Property.ofValue(pdf)).password(wrong).everyNPages(Property.ofValue(1)).build().run(runContext()),
            () -> Read.builder().from(Property.ofValue(pdf)).password(wrong).build().run(runContext()),
            () -> Update.builder().from(Property.ofValue(pdf)).password(wrong).build().run(runContext()),
            () -> Fill.builder().from(Property.ofValue(pdf)).password(wrong).fields(Property.ofValue(Map.of("full_name", "x"))).build().run(runContext())
        );
        for (Executable task : tasks) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, task);
            assertThat(error.getMessage(), containsString("password is missing or wrong"));
        }
    }

    @Test
    void shouldFailClearlyOnACorruptFileInEveryTask() throws Exception {
        String pdf = Fixtures.put(storage, Fixtures.corrupt(), ".pdf").toString();
        String good = Fixtures.put(storage, Fixtures.textPdf("a"), ".pdf").toString();

        List<Executable> tasks = List.of(
            () -> Render.builder().from(Property.ofValue(pdf)).build().run(runContext()),
            () -> io.kestra.plugin.pdfbox.image.Extract.builder().from(Property.ofValue(pdf)).build().run(runContext()),
            () -> Split.builder().from(Property.ofValue(pdf)).everyNPages(Property.ofValue(1)).build().run(runContext()),
            () -> Merge.builder().files(Property.ofValue(List.of(good, pdf))).build().run(runContext()),
            () -> Read.builder().from(Property.ofValue(pdf)).build().run(runContext()),
            () -> Update.builder().from(Property.ofValue(pdf)).build().run(runContext()),
            () -> Encrypt.builder().from(Property.ofValue(pdf)).ownerPassword(Property.ofValue("o")).build().run(runContext()),
            () -> Fill.builder().from(Property.ofValue(pdf)).fields(Property.ofValue(Map.of("a", "b"))).build().run(runContext())
        );
        for (Executable task : tasks) {
            IOException error = assertThrows(IOException.class, task);
            assertThat(error.getMessage(), containsString("Unable to read"));
        }
    }

    // --- empty PDFs and page ranges ---

    @Test
    void shouldSayThatAnEmptyPdfHasNoPages() throws Exception {
        String pdf = Fixtures.put(storage, Fixtures.emptyPdf(), ".pdf").toString();

        List<Executable> tasks = List.of(
            () -> Extract.builder().from(Property.ofValue(pdf)).build().run(runContext()),
            () -> Render.builder().from(Property.ofValue(pdf)).build().run(runContext()),
            () -> Split.builder().from(Property.ofValue(pdf)).everyNPages(Property.ofValue(1)).build().run(runContext())
        );
        for (Executable task : tasks) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, task);
            assertThat(error.getMessage(), is("The PDF has no pages."));
        }
    }

    @Test
    void shouldSayWhenTheStartPageIsPastTheEnd() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a", "b"), ".pdf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .startPage(Property.ofValue(5))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), is("`startPage` 5 is past the last page: the PDF has 2 page(s)."));
    }

    // --- size caps ---

    @Test
    void shouldRefuseToRenderAPageAboveThePixelLimitBeforeRendering() throws Exception {
        // 14,400 x 14,400 points (the PDF maximum) at 150 DPI is about 900 million pixels.
        URI pdf = Fixtures.put(storage, Fixtures.pagePdf(14_400, 14_400), ".pdf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Render.builder()
            .from(Property.ofValue(pdf.toString()))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("`maxPagePixels`"));
    }

    @Test
    void shouldRefuseAnImageAboveThePixelLimitWithoutDecodingIt() throws Exception {
        URI png = Fixtures.put(storage, Fixtures.png(200, 100), ".png");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> FromImages.builder()
            .images(Property.ofValue(List.of(png.toString())))
            .maxImagePixels(Property.ofValue(10_000L))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("20000 pixels"));
    }

    @Test
    void shouldRefuseMoreImagesThanTheLimit() throws Exception {
        String png = Fixtures.put(storage, Fixtures.png(10, 10), ".png").toString();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> FromImages.builder()
            .images(Property.ofValue(List.of(png, png, png)))
            .maxImages(Property.ofValue(2))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("`maxImages`"));
    }

    @Test
    void shouldRejectANegativeMargin() throws Exception {
        String png = Fixtures.put(storage, Fixtures.png(10, 10), ".png").toString();

        assertThrows(IllegalArgumentException.class, () -> FromImages.builder()
            .images(Property.ofValue(List.of(png)))
            .margin(Property.ofValue(-5f))
            .build()
            .run(runContext()));
    }

    @Test
    void shouldCapTheNumberOfSplitPartsAndMergedFiles() throws Exception {
        String pdf = Fixtures.put(storage, Fixtures.textPdf("a", "b", "c"), ".pdf").toString();

        assertThrows(IllegalArgumentException.class, () -> Split.builder()
            .from(Property.ofValue(pdf))
            .everyNPages(Property.ofValue(1))
            .maxParts(Property.ofValue(2))
            .build()
            .run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> Merge.builder()
            .files(Property.ofValue(List.of(pdf, pdf, pdf)))
            .maxFiles(Property.ofValue(2))
            .build()
            .run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> Extract.builder()
            .from(Property.ofValue(pdf))
            .maxPages(Property.ofValue(2))
            .build()
            .run(runContext()));
    }

    // --- smaller fixes ---

    @Test
    void shouldStorePerPageTextInsteadOfReturningItInline() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("alpha", "beta"), ".pdf");
        RunContext runContext = runContext();

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .perPage(Property.ofValue(true))
            .store(Property.ofValue(true))
            .build()
            .run(runContext);

        assertThat(output.getPages(), nullValue());
        assertThat(output.getPageUris(), hasSize(2));
        try (var in = runContext.storage().getFile(output.getPageUris().get(1))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8), containsString("beta"));
        }
    }

    @Test
    void shouldClearAFieldWhoseValueIsNull() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.formPdf(), ".pdf");
        RunContext runContext = runContext();
        Map<String, String> values = new HashMap<>();
        values.put("full_name", null);
        values.put("remote", null);

        Fill.Output output = Fill.builder()
            .from(Property.ofValue(pdf.toString()))
            .fields(Property.ofValue(values))
            .build()
            .run(runContext);

        assertThat(output.getFilledFields(), is(2));
    }

    @Test
    void shouldDropTheXfaLayerOfAHybridFormSoReadersShowTheFilledValues() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.hybridFormPdf(), ".pdf");
        RunContext runContext = runContext();

        Fill.Output output = Fill.builder()
            .from(Property.ofValue(pdf.toString()))
            .fields(Property.ofValue(Map.of("full_name", "Jane Doe")))
            .build()
            .run(runContext);

        try (PDDocument filled = Fixtures.read(runContext, output.getUri())) {
            PDAcroForm form = filled.getDocumentCatalog().getAcroForm();
            assertThat(form.hasXFA(), is(false));
            assertThat(form.getField("full_name").getValueAsString(), is("Jane Doe"));
        }
    }

    private URI encrypted(byte[] pdf) throws Exception {
        return Fixtures.put(storage, Fixtures.encrypt(pdf, "user", "owner"), ".pdf");
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of());
    }
}
