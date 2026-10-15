package io.kestra.plugin.pdfbox.document;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pdfbox.AbstractPdfboxTask;
import io.swagger.v3.oas.annotations.media.Schema;
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
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.net.URI;
import java.util.Iterator;
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
    @Schema(title = "The images, as internal storage URIs, one page each in order")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<List<String>> images;

    @Schema(title = "Page size")
    @Builder.Default
    private Property<PageSize> pageSize = Property.ofValue(PageSize.A4);

    @Schema(title = "Page orientation, for fixed page sizes")
    @Builder.Default
    private Property<Orientation> orientation = Property.ofValue(Orientation.PORTRAIT);

    @Schema(title = "Margin around the image, in points (1/72 inch). Ignored for `IMAGE` page size")
    @Min(0)
    @Builder.Default
    private Property<Float> margin = Property.ofValue(0f);

    @Schema(title = "Maximum number of images, so one page each")
    @Min(1)
    @Builder.Default
    private Property<Integer> maxImages = Property.ofValue(500);

    @Schema(
        title = "Largest image accepted, in pixels (width times height)",
        description = "Read from the image header before decoding, so an oversized image fails without exhausting the worker's memory. The default, 40 million pixels, fits an A4 page scanned at 600 DPI."
    )
    @Min(1)
    @Builder.Default
    private Property<Long> maxImagePixels = Property.ofValue(40_000_000L);

    @Override
    public Output run(RunContext runContext) throws Exception {
        List<String> uris = runContext.render(images).asList(String.class);
        if (uris.isEmpty()) {
            throw new IllegalArgumentException("At least one image is required.");
        }
        PageSize size = runContext.render(pageSize).as(PageSize.class).orElse(PageSize.A4);
        Orientation pageOrientation = runContext.render(orientation).as(Orientation.class).orElse(Orientation.PORTRAIT);
        float pageMargin = runContext.render(margin).as(Float.class).orElse(0f);
        if (pageMargin < 0) {
            throw new IllegalArgumentException("`margin` must not be negative, got %s.".formatted(pageMargin));
        }
        int imageLimit = positive(runContext, maxImages, 500, "maxImages");
        if (uris.size() > imageLimit) {
            throw new IllegalArgumentException(
                "%d images is more than `maxImages` (%d); split the list or raise the limit.".formatted(uris.size(), imageLimit)
            );
        }
        long pixelLimit = runContext.render(maxImagePixels).as(Long.class).orElse(40_000_000L);
        if (pixelLimit < 1) {
            throw new IllegalArgumentException("`maxImagePixels` must be at least 1, got %d.".formatted(pixelLimit));
        }

        try (PDDocument document = new PDDocument()) {
            for (String uri : uris) {
                File file = download(runContext, uri, extensionOf(uri)).toFile();
                checkPixels(file, uri, pixelLimit);
                PDImageXObject image;
                try {
                    image = PDImageXObject.createFromFileByContent(file, document);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("'%s' is not a supported image: %s".formatted(uri, e.getMessage()), e);
                }

                PDRectangle box = size == PageSize.IMAGE
                    ? new PDRectangle(image.getWidth(), image.getHeight())
                    : size.rectangle(pageOrientation);
                PDPage page = new PDPage(box);
                document.addPage(page);

                float inset = size == PageSize.IMAGE ? 0 : pageMargin;
                float maxWidth = box.getWidth() - 2 * inset;
                float maxHeight = box.getHeight() - 2 * inset;
                if (maxWidth <= 0 || maxHeight <= 0) {
                    throw new IllegalArgumentException("`margin` %s leaves no room on the page.".formatted(pageMargin));
                }
                float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
                float width = image.getWidth() * scale;
                float height = image.getHeight() * scale;
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(image, (box.getWidth() - width) / 2, (box.getHeight() - height) / 2, width, height);
                }
            }
            URI result = save(runContext, document);
            return Output.builder().uri(result).pageCount(document.getNumberOfPages()).build();
        }
    }

    /** Reads only the image header, so the size check happens before any pixel is decoded. */
    static void checkPixels(File file, String uri, long limit) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IllegalArgumentException("'%s' is not a supported image (PNG, JPEG, GIF, BMP or TIFF).".formatted(uri));
            }
            ImageReader reader = readers.next();
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
