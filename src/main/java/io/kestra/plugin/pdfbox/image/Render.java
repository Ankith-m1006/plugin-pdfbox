package io.kestra.plugin.pdfbox.image;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pdfbox.AbstractPdfboxTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Render PDF pages to images",
    description = "Renders each page of a range to a PNG or JPEG image in internal storage, for example as input to OCR or an AI vision task. To bound memory on large or hostile files, at most `maxPages` pages are rendered and the resolution is capped at 600 DPI."
)
@Plugin(
    examples = {
        @Example(
            title = "Render the first page of an invoice to a PNG at 200 DPI",
            full = true,
            code = """
                id: pdf_render_page
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: render
                    type: io.kestra.plugin.pdfbox.image.Render
                    from: "{{ inputs.file }}"
                    endPage: 1
                    dpi: 200
                """
        )
    }
)
public class Render extends AbstractPdfboxTask implements RunnableTask<Render.Output> {
    static final int MAX_DPI = 600;
    static final int MAX_PAGES = 10_000;
    static final long MAX_PAGE_PIXELS = 400_000_000L;

    @Schema(title = "The PDF to render, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "main")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "First page to render, starting at 1", description = "Default 1.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) Integer> startPage = Property.ofValue(1);

    @Schema(title = "Last page to render, inclusive", description = "Defaults to the last page.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> endPage;

    @Schema(title = "Resolution in dots per inch", description = "Default 150, between 36 and 600.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(36) @Max(MAX_DPI) Integer> dpi = Property.ofValue(150);

    @Schema(title = "Image format", description = "Default `PNG`.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Format> format = Property.ofValue(Format.PNG);

    @Schema(title = "Render the pages in grayscale instead of color", description = "Default `false`.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> grayscale = Property.ofValue(false);

    @Schema(
        title = "Maximum number of pages to render",
        description = "The task fails instead of rendering more pages than this, so a very long document cannot fill the worker's memory and storage. Default 100, between 1 and 10,000."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_PAGES) Integer> maxPages = Property.ofValue(100);

    @Schema(
        title = "Largest page image to render, in pixels (width times height)",
        description = "Checked from the page size and the DPI before rendering, so a page with a huge media box cannot exhaust the worker's memory. Default 40,000,000, which fits an A4 page at 600 DPI; between 1 and 400,000,000."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_PAGE_PIXELS) Long> maxPagePixels = Property.ofValue(40_000_000L);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int rDpi = runContext.render(dpi).as(Integer.class).orElse(150);
        if (rDpi < 36 || rDpi > MAX_DPI) {
            throw new IllegalArgumentException("`dpi` must be between 36 and %d, got %d.".formatted(MAX_DPI, rDpi));
        }
        var rFormat = runContext.render(format).as(Format.class).orElse(Format.PNG);
        boolean rGrayscale = runContext.render(grayscale).as(Boolean.class).orElse(false);
        int rMaxPages = boundedInt(runContext, maxPages, 100, MAX_PAGES, "maxPages");
        long rMaxPagePixels = boundedLong(runContext, maxPagePixels, 40_000_000L, MAX_PAGE_PIXELS, "maxPagePixels");

        var uris = new ArrayList<URI>();
        try (PDDocument document = load(runContext, from, password)) {
            var range = pageRange(runContext, startPage, endPage, document.getNumberOfPages());
            checkPageLimit(range[1] - range[0] + 1, rMaxPages, "Rendering");

            var renderer = new PDFRenderer(document);
            var type = rGrayscale ? ImageType.GRAY : (rFormat == Format.JPEG ? ImageType.RGB : ImageType.ARGB);
            for (int page = range[0]; page <= range[1]; page++) {
                checkPixels(document.getPage(page - 1).getCropBox(), rDpi, rMaxPagePixels, page);
                var image = renderer.renderImageWithDPI(page - 1, rDpi, type);
                var file = runContext.workingDir().createTempFile(rFormat.extension);
                if (!ImageIO.write(image, rFormat.writer, file.toFile())) {
                    throw new IllegalStateException("No image writer is available for " + rFormat);
                }
                image.flush();
                uris.add(runContext.storage().putFile(file.toFile()));
            }
        }
        runContext.logger().info("Rendered {} page(s) at {} DPI.", uris.size(), rDpi);
        return Output.builder().uris(uris).count(uris.size()).build();
    }

    static void checkPixels(PDRectangle box, int dpi, long limit, int page) {
        double scale = dpi / 72.0;
        long pixels = (long) Math.ceil(box.getWidth() * scale) * (long) Math.ceil(box.getHeight() * scale);
        if (pixels > limit) {
            throw new IllegalArgumentException(
                "Page %d at %d DPI would be %d pixels, more than `maxPagePixels` (%d); lower the DPI or raise the limit."
                    .formatted(page, dpi, pixels, limit)
            );
        }
    }

    public enum Format {
        PNG("png", ".png"),
        JPEG("jpeg", ".jpg");

        final String writer;
        final String extension;

        Format(String writer, String extension) {
            this.writer = writer;
            this.extension = extension;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URIs of the page images, in page order")
        private final List<URI> uris;

        @Schema(title = "Number of rendered pages")
        private final Integer count;
    }
}
