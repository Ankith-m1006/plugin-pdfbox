package io.kestra.plugin.pdfbox.document;

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
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.io.File;
import java.net.URI;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Build a PDF from images",
    description = "Creates one page per image (PNG, JPEG, GIF, BMP or TIFF) from internal storage, in the order given. With a fixed page size, each image is scaled to fit inside the margins without distortion and centered; with `IMAGE`, each page takes the size of its image."
)
@Plugin(
    examples = {
        @Example(
            title = "Turn scanned receipts into one A4 PDF",
            full = true,
            code = """
                id: pdf_from_images
                namespace: company.team

                inputs:
                  - id: first
                    type: FILE
                  - id: second
                    type: FILE

                tasks:
                  - id: pdf
                    type: io.kestra.plugin.pdfbox.document.FromImages
                    images:
                      - "{{ inputs.first }}"
                      - "{{ inputs.second }}"
                    pageSize: A4
                    margin: 36
                """
        )
    }
)
public class FromImages extends AbstractPdfboxTask implements RunnableTask<FromImages.Output> {
    static final int MAX_IMAGES = 10_000;
    static final long MAX_IMAGE_PIXELS = 400_000_000L;

    @Schema(title = "The images, as internal storage URIs, one page each in order")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "main")
    private Property<List<String>> images;

    @Schema(title = "Page size", description = "Default `A4`; `IMAGE` makes each page the size of its image.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<PageSize> pageSize = Property.ofValue(PageSize.A4);

    @Schema(title = "Page orientation, for fixed page sizes", description = "Default `PORTRAIT`.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Orientation> orientation = Property.ofValue(Orientation.PORTRAIT);

    @Schema(title = "Margin around the image, in points (1/72 inch)", description = "Ignored for the `IMAGE` page size. Default 0, at least 0.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(0) Float> margin = Property.ofValue(0f);

    @Schema(title = "Maximum number of images, so one page each", description = "Default 500, between 1 and 10,000.")
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_IMAGES) Integer> maxImages = Property.ofValue(500);

    @Schema(
        title = "Largest image accepted, in pixels (width times height)",
        description = "Read from the image header before decoding, so an oversized image fails without exhausting the worker's memory. Default 40,000,000, which fits an A4 page scanned at 600 DPI; between 1 and 400,000,000."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_IMAGE_PIXELS) Long> maxImagePixels = Property.ofValue(40_000_000L);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rImages = runContext.render(images).asList(String.class);
        if (rImages.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required.");
        }
        var rPageSize = runContext.render(pageSize).as(PageSize.class).orElse(PageSize.A4);
        var rOrientation = runContext.render(orientation).as(Orientation.class).orElse(Orientation.PORTRAIT);
        float rMargin = runContext.render(margin).as(Float.class).orElse(0f);
        if (rMargin < 0) {
            throw new IllegalArgumentException("`margin` must not be negative, got %s.".formatted(rMargin));
        }
        int rMaxImages = boundedInt(runContext, maxImages, 500, MAX_IMAGES, "maxImages");
        if (rImages.size() > rMaxImages) {
            throw new IllegalArgumentException(
                "%d images is more than `maxImages` (%d); split the list or raise the limit.".formatted(rImages.size(), rMaxImages)
            );
        }
        long rMaxImagePixels = boundedLong(runContext, maxImagePixels, 40_000_000L, MAX_IMAGE_PIXELS, "maxImagePixels");

        try (PDDocument document = new PDDocument()) {
            for (var uri : rImages) {
                var file = download(runContext, uri, extensionOf(uri)).toFile();
                checkPixels(file, uri, rMaxImagePixels);
                PDImageXObject image;
                try {
                    image = PDImageXObject.createFromFileByContent(file, document);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("'%s' is not a supported image: %s".formatted(uri, e.getMessage()), e);
                }

                var box = rPageSize == PageSize.IMAGE
                    ? new PDRectangle(image.getWidth(), image.getHeight())
                    : rPageSize.rectangle(rOrientation);
                var page = new PDPage(box);
                document.addPage(page);

                float inset = rPageSize == PageSize.IMAGE ? 0 : rMargin;
                float maxWidth = box.getWidth() - 2 * inset;
                float maxHeight = box.getHeight() - 2 * inset;
                if (maxWidth <= 0 || maxHeight <= 0) {
                    throw new IllegalArgumentException("`margin` %s leaves no room on the page.".formatted(rMargin));
                }
                float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
                float width = image.getWidth() * scale;
                float height = image.getHeight() * scale;
                try (var content = new PDPageContentStream(document, page)) {
                    content.drawImage(image, (box.getWidth() - width) / 2, (box.getHeight() - height) / 2, width, height);
                }
            }
            var result = save(runContext, document);
            return Output.builder().uri(result).pageCount(document.getNumberOfPages()).build();
        }
    }

    // Reads only the image header, so the size check happens before any pixel is decoded.
    static void checkPixels(File file, String uri, long limit) throws Exception {
        try (var in = ImageIO.createImageInputStream(file)) {
            var readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IllegalArgumentException("'%s' is not a supported image (PNG, JPEG, GIF, BMP or TIFF).".formatted(uri));
            }
            var reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > limit) {
                    throw new IllegalArgumentException(
                        "'%s' is %d pixels, more than `maxImagePixels` (%d).".formatted(uri, pixels, limit)
                    );
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static String extensionOf(String uri) {
        int dot = uri.lastIndexOf('.');
        return dot > uri.lastIndexOf('/') ? uri.substring(dot) : ".img";
    }

    public enum PageSize {
        A3(PDRectangle.A3),
        A4(PDRectangle.A4),
        A5(PDRectangle.A5),
        LETTER(PDRectangle.LETTER),
        LEGAL(PDRectangle.LEGAL),
        IMAGE(null);

        private final PDRectangle portrait;

        PageSize(PDRectangle portrait) {
            this.portrait = portrait;
        }

        PDRectangle rectangle(Orientation orientation) {
            return orientation == Orientation.LANDSCAPE
                ? new PDRectangle(portrait.getHeight(), portrait.getWidth())
                : portrait;
        }
    }

    public enum Orientation {
        PORTRAIT,
        LANDSCAPE
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the PDF")
        private final URI uri;

        @Schema(title = "Number of pages")
        private final Integer pageCount;
    }
}
