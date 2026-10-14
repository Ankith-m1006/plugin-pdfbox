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
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
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
    @Schema(title = "The PDF to read, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(title = "Password of an encrypted PDF")
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "First page to read, starting at 1")
    @Builder.Default
    private Property<Integer> startPage = Property.ofValue(1);

    @Schema(title = "Last page to read, inclusive. Defaults to the last page")
    private Property<Integer> endPage;

    @Schema(title = "Maximum number of images to extract before failing")
    @Builder.Default
    private Property<Integer> maxImages = Property.ofValue(500);

    @Schema(
        title = "Largest image to extract, in pixels (width times height)",
        description = "Bigger images are skipped with a warning. The default, 40 million pixels, fits an A4 page scanned at 600 DPI."
    )
    @Builder.Default
    private Property<Long> maxImagePixels = Property.ofValue(40_000_000L);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int imageLimit = runContext.render(maxImages).as(Integer.class).orElse(500);
        long pixelLimit = runContext.render(maxImagePixels).as(Long.class).orElse(40_000_000L);

        List<URI> uris = new ArrayList<>();
        try (PDDocument document = load(runContext, from, password)) {
            int[] range = pageRange(runContext, startPage, endPage, document.getNumberOfPages());
            Set<Object> seen = new HashSet<>();
            int skipped = 0;
            for (int page = range[0]; page <= range[1]; page++) {
                PDPage pdPage = document.getPage(page - 1);
                skipped += collect(runContext, pdPage.getResources(), seen, uris, imageLimit, pixelLimit, 0);
            }
            if (skipped > 0) {
                runContext.logger().warn("Skipped {} images larger than {} pixels.", skipped, pixelLimit);
            }
        }
        runContext.logger().info("Extracted {} images.", uris.size());
        return Output.builder().uris(uris).count(uris.size()).build();
    }

    private static int collect(RunContext runContext, PDResources resources, Set<Object> seen, List<URI> uris,
                               int imageLimit, long pixelLimit, int depth) throws Exception {
        if (resources == null || depth > 10) {
            return 0;
        }
        int skipped = 0;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
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
                BufferedImage buffered = image.getImage();
                Path file = runContext.workingDir().createTempFile(".png");
                ImageIO.write(buffered, "png", file.toFile());
                buffered.flush();
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
