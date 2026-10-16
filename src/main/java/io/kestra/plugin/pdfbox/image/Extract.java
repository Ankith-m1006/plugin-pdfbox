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
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Extract the embedded images of a PDF",
    description = "Saves every image embedded in the pages of a range, including images inside form XObjects, as PNG files in internal storage. An image reused on several pages is saved once. To bound memory, extraction stops with an error after `maxImages` images and skips images larger than `maxImagePixels`."
)
@Plugin(
    examples = {
        @Example(
            title = "Extract the product photos embedded in a catalog",
            full = true,
            code = """
                id: pdf_extract_images
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: images
                    type: io.kestra.plugin.pdfbox.image.Extract
                    from: "{{ inputs.file }}"

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ outputs.images.count }} images"
                """
        )
    }
)
public class Extract extends AbstractPdfboxTask implements RunnableTask<Extract.Output> {
    static final int MAX_IMAGES = 10_000;
    static final long MAX_IMAGE_PIXELS = 400_000_000L;

    @Schema(title = "The PDF to read, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "main")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "First page to read, starting at 1", description = "Default 1.")
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) Integer> startPage = Property.ofValue(1);

    @Schema(title = "Last page to read, inclusive", description = "Defaults to the last page.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> endPage;

    @Schema(
        title = "Maximum number of images to extract before failing",
        description = "Default 500, between 1 and 10,000."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_IMAGES) Integer> maxImages = Property.ofValue(500);

    @Schema(
        title = "Largest image to extract, in pixels (width times height)",
        description = "Bigger images are skipped with a warning. Default 40,000,000, which fits an A4 page scanned at 600 DPI; between 1 and 400,000,000."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<@Min(1) @Max(MAX_IMAGE_PIXELS) Long> maxImagePixels = Property.ofValue(40_000_000L);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int rMaxImages = boundedInt(runContext, maxImages, 500, MAX_IMAGES, "maxImages");
        long rMaxImagePixels = boundedLong(runContext, maxImagePixels, 40_000_000L, MAX_IMAGE_PIXELS, "maxImagePixels");

        var uris = new ArrayList<URI>();
        try (PDDocument document = load(runContext, from, password)) {
            var range = pageRange(runContext, startPage, endPage, document.getNumberOfPages());
            var seen = new HashSet<Object>();
            int skipped = 0;
            for (int page = range[0]; page <= range[1]; page++) {
                var pdPage = document.getPage(page - 1);
                skipped += collect(runContext, pdPage.getResources(), seen, uris, rMaxImages, rMaxImagePixels, 0);
            }
            if (skipped > 0) {
                runContext.logger().warn("Skipped {} image(s) larger than {} pixels.", skipped, rMaxImagePixels);
            }
        }
        runContext.logger().info("Extracted {} image(s).", uris.size());
        return Output.builder().uris(uris).count(uris.size()).build();
    }

    private static int collect(RunContext runContext, PDResources resources, Set<Object> seen, List<URI> uris,
                               int imageLimit, long pixelLimit, int depth) throws Exception {
        if (resources == null || depth > 10) {
            return 0;
        }
        int skipped = 0;
        for (COSName name : resources.getXObjectNames()) {
            var xObject = resources.getXObject(name);
            if (xObject == null || !seen.add(xObject.getCOSObject())) {
                continue;
            }
            if (xObject instanceof PDFormXObject form) {
                skipped += collect(runContext, form.getResources(), seen, uris, imageLimit, pixelLimit, depth + 1);
            } else if (xObject instanceof PDImageXObject image) {
                if ((long) image.getWidth() * image.getHeight() > pixelLimit) {
                    skipped++;
                    continue;
                }
                if (uris.size() >= imageLimit) {
                    throw new IllegalArgumentException(
                        "The PDF holds more than `maxImages` (%d) images; narrow the page range or raise the limit.".formatted(imageLimit)
                    );
                }
                var buffered = image.getImage();
                var file = runContext.workingDir().createTempFile(".png");
                boolean written = ImageIO.write(buffered, "png", file.toFile());
                buffered.flush();
                if (!written) {
                    throw new IllegalStateException("No PNG writer could encode an image of the PDF.");
                }
                uris.add(runContext.storage().putFile(file.toFile()));
            }
        }
        return skipped;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URIs of the extracted PNG images")
        private final List<URI> uris;

        @Schema(title = "Number of extracted images")
        private final Integer count;
    }
}
