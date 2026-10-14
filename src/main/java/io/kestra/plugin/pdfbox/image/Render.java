package io.kestra.plugin.pdfbox.image;

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
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
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

    @Schema(title = "The PDF to render, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "First page to render, starting at 1")
    @Builder.Default
    private Property<Integer> startPage = Property.ofValue(1);

    @Schema(title = "Last page to render, inclusive. Defaults to the last page")
    private Property<Integer> endPage;

    @Schema(title = "Resolution in dots per inch, between 36 and 600")
    @Builder.Default
    private Property<Integer> dpi = Property.ofValue(150);

    @Schema(title = "Image format")
    @Builder.Default
    private Property<Format> format = Property.ofValue(Format.PNG);

    @Schema(title = "Render the pages in grayscale instead of color")
    @Builder.Default
    private Property<Boolean> grayscale = Property.ofValue(false);

    @Schema(
        title = "Maximum number of pages to render",
        description = "The task fails instead of rendering more pages than this, so a very long document cannot fill the worker's memory and storage."
    )
    @Builder.Default
    private Property<Integer> maxPages = Property.ofValue(100);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int resolution = runContext.render(dpi).as(Integer.class).orElse(150);
        if (resolution < 36 || resolution > MAX_DPI) {
            throw new IllegalArgumentException("`dpi` must be between 36 and %d, got %d.".formatted(MAX_DPI, resolution));
        }
        Format imageFormat = runContext.render(format).as(Format.class).orElse(Format.PNG);
        boolean gray = runContext.render(grayscale).as(Boolean.class).orElse(false);
        int limit = runContext.render(maxPages).as(Integer.class).orElse(100);

        List<URI> uris = new ArrayList<>();
        try (PDDocument document = load(runContext, from, password)) {
            int[] range = pageRange(runContext, startPage, endPage, document.getNumberOfPages());
            int pages = range[1] - range[0] + 1;
            if (pages > limit) {
                throw new IllegalArgumentException(
                    "Rendering %d pages exceeds `maxPages` (%d); narrow the page range or raise the limit.".formatted(pages, limit)
                );
            }

            PDFRenderer renderer = new PDFRenderer(document);
            ImageType type = gray ? ImageType.GRAY : (imageFormat == Format.JPEG ? ImageType.RGB : ImageType.ARGB);
            for (int page = range[0]; page <= range[1]; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page - 1, resolution, type);
                Path file = runContext.workingDir().createTempFile(imageFormat.extension);
                if (!ImageIO.write(image, imageFormat.writer, file.toFile())) {
                    throw new IllegalStateException("No image writer is available for " + imageFormat);
                }
                image.flush();
                uris.add(runContext.storage().putFile(file.toFile()));
            }
        }
        runContext.logger().info("Rendered {} page(s) at {} DPI.", uris.size(), resolution);
        return Output.builder().uris(uris).count(uris.size()).build();
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
