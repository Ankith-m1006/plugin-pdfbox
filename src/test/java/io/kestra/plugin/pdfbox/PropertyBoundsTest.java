package io.kestra.plugin.pdfbox;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.pdfbox.document.FromHtml;
import io.kestra.plugin.pdfbox.document.FromImages;
import io.kestra.plugin.pdfbox.document.Merge;
import io.kestra.plugin.pdfbox.document.Split;
import io.kestra.plugin.pdfbox.image.Render;
import io.kestra.plugin.pdfbox.text.Extract;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

// Runs Kestra's model validation, the one a flow goes through when it is saved; bounds sit on the inner type (`Property<@Min(1) Integer>`).
@KestraTest
class PropertyBoundsTest {
    @Inject
    private ModelValidator modelValidator;

    @Test
    void shouldAcceptTasksWithinTheirBounds() {
        List<Object> tasks = List.of(
            Render.builder().id(IdUtils.create()).type(Render.class.getName()).from(Property.ofValue("kestra:///a.pdf")).dpi(Property.ofValue(300)).build(),
            Extract.builder().id(IdUtils.create()).type(Extract.class.getName()).from(Property.ofValue("kestra:///a.pdf")).startPage(Property.ofValue(2)).build(),
            Split.builder().id(IdUtils.create()).type(Split.class.getName()).from(Property.ofValue("kestra:///a.pdf")).everyNPages(Property.ofValue(1)).build(),
            Merge.builder().id(IdUtils.create()).type(Merge.class.getName()).files(Property.ofValue(List.of("kestra:///a.pdf", "kestra:///b.pdf"))).build(),
            FromImages.builder().id(IdUtils.create()).type(FromImages.class.getName()).images(Property.ofValue(List.of("kestra:///a.png"))).margin(Property.ofValue(10f)).build(),
            FromHtml.builder().id(IdUtils.create()).type(FromHtml.class.getName()).html(Property.ofValue("<html/>")).build()
        );
        for (Object task : tasks) {
            assertThat(task.getClass().getSimpleName(), modelValidator.isValid(task).isEmpty(), is(true));
        }
    }

    @Test
    void shouldRejectValuesOutsideTheBounds() {
        assertViolation(Render.builder().id(IdUtils.create()).type(Render.class.getName()).from(Property.ofValue("kestra:///a.pdf")).dpi(Property.ofValue(900)).build(), "dpi");
        assertViolation(Split.builder().id(IdUtils.create()).type(Split.class.getName()).from(Property.ofValue("kestra:///a.pdf")).everyNPages(Property.ofValue(0)).build(), "everyNPages");
        assertViolation(Extract.builder().id(IdUtils.create()).type(Extract.class.getName()).from(Property.ofValue("kestra:///a.pdf")).startPage(Property.ofValue(0)).build(), "startPage");
        assertViolation(Render.builder().id(IdUtils.create()).type(Render.class.getName()).from(Property.ofValue("kestra:///a.pdf")).maxPages(Property.ofValue(10_001)).build(), "maxPages");
        assertViolation(Render.builder().id(IdUtils.create()).type(Render.class.getName()).from(Property.ofValue("kestra:///a.pdf")).maxPagePixels(Property.ofValue(400_000_001L)).build(), "maxPagePixels");
        assertViolation(Merge.builder().id(IdUtils.create()).type(Merge.class.getName()).files(Property.ofValue(List.of("kestra:///a.pdf", "kestra:///b.pdf"))).maxFiles(Property.ofValue(10_001)).build(), "maxFiles");
        assertViolation(Split.builder().id(IdUtils.create()).type(Split.class.getName()).from(Property.ofValue("kestra:///a.pdf")).everyNPages(Property.ofValue(1)).maxParts(Property.ofValue(100_001)).build(), "maxParts");
        assertViolation(FromHtml.builder().id(IdUtils.create()).type(FromHtml.class.getName()).html(Property.ofValue("<html/>")).maxResourceBytes(Property.ofValue(2L * 1024 * 1024 * 1024)).build(), "maxResourceBytes");
        assertViolation(FromImages.builder().id(IdUtils.create()).type(FromImages.class.getName()).images(Property.ofValue(List.of("kestra:///a.png"))).margin(Property.ofValue(-1f)).build(), "margin");
    }

    private void assertViolation(Object task, String property) {
        var violation = modelValidator.isValid(task);
        assertThat(violation.isPresent(), is(true));
        assertThat(violation.get().getMessage(), containsString(property));
    }
}
