package io.kestra.plugin.pdfbox.text;

import java.nio.charset.StandardCharsets;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.plugin.pdfbox.Fixtures;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ExtractTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storage;

    @Test
    void shouldExtractTextOfThePageRange() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("first page", "second page", "third page"), ".pdf");

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .startPage(Property.ofValue(2))
            .endPage(Property.ofValue(3))
            .build()
            .run(runContext());

        assertThat(output.getText(), allOf(containsString("second page"), containsString("third page")));
        assertThat(output.getText(), not(containsString("first page")));
        assertThat(output.getPageCount(), is(3));
        assertThat(output.getPages(), nullValue());
    }

    @Test
    void shouldReturnEachPageWhenPerPageIsSet() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("alpha", "beta"), ".pdf");

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .perPage(Property.ofValue(true))
            .build()
            .run(runContext());

        assertThat(output.getPages(), hasSize(2));
        assertThat(output.getPages().get(0), containsString("alpha"));
        assertThat(output.getPages().get(1), containsString("beta"));
    }

    @Test
    void shouldWriteTheTextToStorageWithFetchTypeStore() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("long report"), ".pdf");
        RunContext runContext = runContext();

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build()
            .run(runContext);

        assertThat(output.getText(), nullValue());
        try (var in = runContext.storage().getFile(output.getUri())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8), containsString("long report"));
        }
    }

    @Test
    void shouldReturnOnlyThePageCountWithFetchTypeNone() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a", "b", "c"), ".pdf");

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build()
            .run(runContext());

        assertThat(output.getPageCount(), is(3));
        assertThat(output.getText(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void shouldRefuseFetchTypeFetchOne() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a"), ".pdf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build()
            .run(runContext()));
        assertThat(error.getMessage(), containsString("FETCH_ONE"));
    }

    @Test
    void shouldClampTheEndPageToTheDocument() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("only page"), ".pdf");

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .endPage(Property.ofValue(10))
            .build()
            .run(runContext());

        assertThat(output.getText(), containsString("only page"));
    }

    @Test
    void shouldRejectAnInvertedPageRange() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a", "b"), ".pdf");

        Extract task = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .startPage(Property.ofValue(2))
            .endPage(Property.ofValue(1))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
    }

    @Test
    void shouldReadAnEncryptedPdfWithItsPassword() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.encrypt(Fixtures.textPdf("secret text"), "user", "owner"), ".pdf");

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .password(Property.ofValue("user"))
            .build()
            .run(runContext());

        assertThat(output.getText(), containsString("secret text"));
    }

    @Test
    void shouldFailClearlyOnAWrongPassword() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.encrypt(Fixtures.textPdf("secret text"), "user", "owner"), ".pdf");

        Extract task = Extract.builder().from(Property.ofValue(pdf.toString())).password(Property.ofValue("nope")).build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(error.getMessage(), containsString("password"));
    }

    @Test
    void shouldFailClearlyOnACorruptFile() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.corrupt(), ".pdf");

        Extract task = Extract.builder().from(Property.ofValue(pdf.toString())).build();

        IOException error = assertThrows(IOException.class, () -> task.run(runContext()));
        assertThat(error.getMessage(), containsString("Unable to read"));
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of());
    }
}
