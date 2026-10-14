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
import org.apache.pdfbox.pdmodel.PDDocument;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Split a PDF into several PDFs",
    description = "Splits by explicit page ranges (`ranges`) or into chunks of `everyNPages` pages. Each part is stored in internal storage; the output lists them in order."
)
@Plugin(
    examples = {
        @Example(
            title = "Split a PDF into one file per page",
            full = true,
            code = """
                id: pdf_split_pages
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: split
                    type: io.kestra.plugin.pdfbox.document.Split
                    from: "{{ inputs.file }}"
                    everyNPages: 1
                """
        ),
        @Example(
            title = "Keep the cover page and the appendix as separate files",
            full = true,
            code = """
                id: pdf_split_ranges
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: split
                    type: io.kestra.plugin.pdfbox.document.Split
                    from: "{{ inputs.file }}"
                    ranges:
                      - "1"
                      - "10-14"
                """
        )
    }
)
public class Split extends AbstractPdfboxTask implements RunnableTask<Split.Output> {
    private static final Pattern RANGE = Pattern.compile("\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?");

    @Schema(title = "The PDF to split, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(
        title = "Page ranges to extract, one output PDF per entry",
        description = "Each entry is a single page (`3`) or an inclusive range (`3-7`), pages numbered from 1. Set either `ranges` or `everyNPages`."
    )
    private Property<List<String>> ranges;

    @Schema(
        title = "Split into chunks of this many pages",
        description = "The last chunk holds the remaining pages. Set either `ranges` or `everyNPages`."
    )
    private Property<Integer> everyNPages;

    @Override
    public Output run(RunContext runContext) throws Exception {
        List<String> rangeList = runContext.render(ranges).asList(String.class);
        Integer chunk = runContext.render(everyNPages).as(Integer.class).orElse(null);
        if (rangeList.isEmpty() == (chunk == null)) {
            throw new IllegalArgumentException("Set exactly one of `ranges` or `everyNPages`.");
        }
        if (chunk != null && chunk < 1) {
            throw new IllegalArgumentException("`everyNPages` must be at least 1, got %d.".formatted(chunk));
        }

        List<URI> uris = new ArrayList<>();
        try (PDDocument document = load(runContext, from, password)) {
            int pageCount = document.getNumberOfPages();
            List<int[]> parts = chunk != null ? chunks(pageCount, chunk) : parse(rangeList, pageCount);
            for (int[] part : parts) {
                try (PDDocument output = new PDDocument()) {
                    for (int page = part[0]; page <= part[1]; page++) {
                        output.importPage(document.getPage(page - 1));
                    }
                    uris.add(save(runContext, output));
                }
            }
            runContext.logger().info("Split {} pages into {} PDFs.", pageCount, uris.size());
        }
        return Output.builder().uris(uris).count(uris.size()).build();
    }

    static List<int[]> chunks(int pageCount, int size) {
        List<int[]> parts = new ArrayList<>();
        for (int start = 1; start <= pageCount; start += size) {
            parts.add(new int[]{start, Math.min(start + size - 1, pageCount)});
        }
        return parts;
    }

    static List<int[]> parse(List<String> ranges, int pageCount) {
        List<int[]> parts = new ArrayList<>();
        for (String range : ranges) {
            Matcher matcher = RANGE.matcher(range);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid page range '%s': use a page like `3` or a range like `3-7`.".formatted(range));
            }
            int start = Integer.parseInt(matcher.group(1));
            int end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
            if (start < 1 || end < start || end > pageCount) {
                throw new IllegalArgumentException(
                    "Page range '%s' is outside the document, which has %d pages.".formatted(range, pageCount)
                );
            }
            parts.add(new int[]{start, end});
        }
        return parts;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URIs of the parts, in order")
        private final List<URI> uris;

        @Schema(title = "Number of parts")
        private final Integer count;
    }
}
