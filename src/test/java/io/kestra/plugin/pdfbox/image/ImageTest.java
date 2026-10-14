package io.kestra.plugin.pdfbox.image;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.plugin.pdfbox.Fixtures;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ImageTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private StorageInterface storage;

    @Test
    void shouldRenderPagesAtTheRequestedDpi() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a", "b"), ".pdf");
        RunContext runContext = runContext();

        Render.Output output = Render.builder()
            .from(Property.ofValue(pdf.toString()))
            .dpi(Property.ofValue(72))
            .build()
            .run(runContext);

        assertThat(output.getCount(), is(2));
        BufferedImage page = image(runContext, output.getUris().get(0));
        // 72 DPI renders one pixel per point; an A4 page is 595.28 x 841.89 points.
        assertThat(page.getWidth(), is(595));
        assertThat(page.getHeight(), is(841));
    }

    @Test
    void shouldRenderJpeg() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("a"), ".pdf");
        RunContext runContext = runContext();

        Render.Output output = Render.builder()
            .from(Property.ofValue(pdf.toString()))
            .format(Property.ofValue(Render.Format.JPEG))
            .dpi(Property.ofValue(50))
            .build()
            .run(runContext);

        assertThat(output.getUris().get(0).toString(), endsWith(".jpg"));
        assertThat(image(runContext, output.getUris().get(0)), notNullValue());
    }

    @Test
    void shouldRefuseToRenderMorePagesThanTheLimit() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("1", "2", "3"), ".pdf");

        Render task = Render.builder().from(Property.ofValue(pdf.toString())).maxPages(Property.ofValue(2)).build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(error.getMessage(), containsString("maxPages"));
    }

    @Test
    void shouldRejectAnOutOfBoundsDpi() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.textPdf("1"), ".pdf");

        Render task = Render.builder().from(Property.ofValue(pdf.toString())).dpi(Property.ofValue(5000)).build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
    }

    @Test
    void shouldExtractEachEmbeddedImage() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.imagePdf(3), ".pdf");
        RunContext runContext = runContext();

        Extract.Output output = Extract.builder().from(Property.ofValue(pdf.toString())).build().run(runContext);

        assertThat(output.getCount(), is(3));
        assertThat(image(runContext, output.getUris().get(0)).getHeight(), is(30));
    }

    @Test
    void shouldStopAtTheImageLimit() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.imagePdf(3), ".pdf");

        Extract task = Extract.builder().from(Property.ofValue(pdf.toString())).maxImages(Property.ofValue(2)).build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
    }

    @Test
    void shouldSkipImagesAboveThePixelLimit() throws Exception {
        URI pdf = Fixtures.put(storage, Fixtures.imagePdf(2), ".pdf");

        Extract.Output output = Extract.builder()
            .from(Property.ofValue(pdf.toString()))
            .maxImagePixels(Property.ofValue(100L))
            .build()
            .run(runContext());

        assertThat(output.getCount(), is(0));
    }

    private static BufferedImage image(RunContext runContext, URI uri) throws Exception {
        try (InputStream in = runContext.storage().getFile(uri)) {
            return ImageIO.read(in);
        }
    }

    private RunContext runContext() {
        return runContextFactory.of(Map.of());
    }
}
