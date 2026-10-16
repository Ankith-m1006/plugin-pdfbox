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
import java.io.OutputStream;
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

    /**
     * Tasks that change an encrypted PDF, like {@code metadata.Update} and {@code form.Fill}, need its owner password:
     * the user password alone must not lift the restrictions set by the owner. Opened with the owner password,
     * PDFBox encrypts the saved copy again with the same protection.
     */
    protected static void requireOwnerPermission(PDDocument document, String task) {
        requireOwnerPermission(document, task, "changes the PDF");
    }

    /**
     * Tasks that write an unencrypted copy, like {@code document.Split}, also need the owner password;
     * the copy is then marked to be saved without its encryption.
     */
    protected static void removeProtection(PDDocument document, String task) {
        requireOwnerPermission(document, task, "writes an unencrypted copy");
        if (document.isEncrypted()) {
            document.setAllSecurityToBeRemoved(true);
        }
    }

    private static void requireOwnerPermission(PDDocument document, String task, String effect) {
        if (document.isEncrypted() && !document.getCurrentAccessPermission().isOwnerPermission()) {
            throw new IllegalArgumentException(
                "The PDF is encrypted and the password given is not its owner password. `%s` %s, "
                    .formatted(task, effect)
                    + "which needs the owner password; pass the owner password, or keep the file protected and only read it."
            );
        }
    }

    protected static URI save(RunContext runContext, PDDocument document) throws Exception {
        Path file = runContext.workingDir().createTempFile(".pdf");
        document.save(file.toFile());
        return runContext.storage().putFile(file.toFile());
    }

    /**
     * Saves a changed copy that keeps the protection of an encrypted input. PDFBox refuses a full save of an
     * encrypted document unless its security is removed or replaced, so the changes are appended as an incremental
     * update, which reuses the original encryption dictionary and keys. Unencrypted documents are saved normally.
     */
    protected static URI saveKeepingProtection(RunContext runContext, PDDocument document) throws Exception {
        if (!document.isEncrypted()) {
            return save(runContext, document);
        }
        Path file = runContext.workingDir().createTempFile(".pdf");
        try (OutputStream out = Files.newOutputStream(file)) {
            document.saveIncremental(out);
        }
        return runContext.storage().putFile(file.toFile());
    }

    protected static int[] pageRange(RunContext runContext, Property<Integer> startPage, Property<Integer> endPage, int pageCount) throws Exception {
        if (pageCount == 0) {
            throw new IllegalArgumentException("The PDF has no pages.");
        }
        int start = runContext.render(startPage).as(Integer.class).orElse(1);
        int end = runContext.render(endPage).as(Integer.class).orElse(pageCount);
        if (start < 1) {
            throw new IllegalArgumentException("`startPage` must be at least 1, got %d.".formatted(start));
        }
        if (start > pageCount) {
            throw new IllegalArgumentException(
                "`startPage` %d is past the last page: the PDF has %d page(s).".formatted(start, pageCount)
            );
        }
        if (end < start) {
            throw new IllegalArgumentException("`endPage` %d is before `startPage` %d.".formatted(end, start));
        }
        return new int[]{start, Math.min(end, pageCount)};
    }

    protected static int checkPageLimit(int pages, int limit, String what) {
        if (pages > limit) {
            throw new IllegalArgumentException(
                "%s %d page(s), more than `maxPages` (%d); narrow the page range or raise the limit.".formatted(what, pages, limit)
            );
        }
        return pages;
    }

    protected static int positive(RunContext runContext, Property<Integer> property, int fallback, String name) throws Exception {
        int value = runContext.render(property).as(Integer.class).orElse(fallback);
        if (value < 1) {
            throw new IllegalArgumentException("`%s` must be at least 1, got %d.".formatted(name, value));
        }
        return value;
    }

    /** Reads at most {@code limit} bytes, failing instead of loading a larger stream into memory. */
    protected static byte[] readLimited(InputStream in, long limit, String source) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
        if (bytes.length > limit) {
            throw new IOException("'%s' is larger than %d bytes.".formatted(source, limit));
        }
        return bytes;
    }
}
