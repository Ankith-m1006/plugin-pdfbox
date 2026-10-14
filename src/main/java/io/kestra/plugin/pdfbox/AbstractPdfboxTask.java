package io.kestra.plugin.pdfbox;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractPdfboxTask extends Task {

    protected static Path download(RunContext runContext, String uri, String extension) throws Exception {
        Path file = runContext.workingDir().createTempFile(extension);
        try (InputStream in = runContext.storage().getFile(URI.create(uri))) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    protected static String required(RunContext runContext, Property<String> property, String name) throws Exception {
        return runContext.render(property).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("The `%s` property is required.".formatted(name)));
    }

    protected static PDDocument load(RunContext runContext, Property<String> from, Property<String> password) throws Exception {
        String uri = required(runContext, from, "from");
        String renderedPassword = runContext.render(password).as(String.class).orElse("");
        return load(download(runContext, uri, ".pdf").toFile(), renderedPassword, uri);
    }

    protected static PDDocument load(File file, String password, String source) throws IOException {
        try {
            return Loader.loadPDF(new RandomAccessReadBufferedFile(file), password);
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException(
                "The PDF '%s' is encrypted and the password is missing or wrong.".formatted(source), e
            );
        } catch (IOException e) {
            throw new IOException("Unable to read '%s' as a PDF: %s".formatted(source, e.getMessage()), e);
        }
    }

    protected static URI save(RunContext runContext, PDDocument document) throws Exception {
        Path file = runContext.workingDir().createTempFile(".pdf");
        document.save(file.toFile());
        return runContext.storage().putFile(file.toFile());
    }

    protected static int[] pageRange(RunContext runContext, Property<Integer> startPage, Property<Integer> endPage, int pageCount) throws Exception {
        int start = runContext.render(startPage).as(Integer.class).orElse(1);
        int end = runContext.render(endPage).as(Integer.class).orElse(pageCount);
        if (start < 1 || end < start) {
            throw new IllegalArgumentException(
                "Invalid page range %d-%d: pages are numbered from 1 and the end page must not be before the start page.".formatted(start, end)
            );
        }
        return new int[]{start, Math.min(end, pageCount)};
    }
}
