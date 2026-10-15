package io.kestra.plugin.pdfbox;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
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

/**
 * Runs Kestra's model validation, the one a flow goes through when it is saved, on tasks with
 * numeric bounds. Bounds sit on the inner type (`Property<@Min(1) Integer>`).
 */
@KestraTest
class PropertyBoundsTest {
    @Inject
    private ModelValidator modelValidator;

    @Test
    void shouldAcceptTasksWithinTheirBounds() {
        List<Object> tasks = List.of(
            Render.builder().id("render").type(Render.class.getName()).from(Property.ofValue("kestra:///a.pdf")).dpi(Property.ofValue(300)).build(),
            Extract.builder().id("text").type(Extract.class.getName()).from(Property.ofValue("kestra:///a.pdf")).startPage(Property.ofValue(2)).build(),
            Split.builder().id("split").type(Split.class.getName()).from(Property.ofValue("kestra:///a.pdf")).everyNPages(Property.ofValue(1)).build(),
            Merge.builder().id("merge").type(Merge.class.getName()).files(Property.ofValue(List.of("kestra:///a.pdf", "kestra:///b.pdf"))).build(),
            FromImages.builder().id("images").type(FromImages.class.getName()).images(Property.ofValue(List.of("kestra:///a.png"))).margin(Property.ofValue(10f)).build(),
            FromHtml.builder().id("html").type(FromHtml.class.getName()).html(Property.ofValue("<html/>")).build()
        );
        for (Object task : tasks) {
            assertThat(task.getClass().getSimpleName(), modelValidator.isValid(task).isEmpty(), is(true));
        }
    }

    @Test
    void shouldRejectValuesOutsideTheBounds() {
        assertViolation(Render.builder().id("render").type(Render.class.getName()).from(Property.ofValue("kestra:///a.pdf")).dpi(Property.ofValue(900)).build(), "dpi");
        assertViolation(Split.builder().id("split").type(Split.class.getName()).from(Property.ofValue("kestra:///a.pdf")).everyNPages(Property.ofValue(0)).build(), "everyNPages");
        assertViolation(Extract.builder().id("text").type(Extract.class.getName()).from(Property.ofValue("kestra:///a.pdf")).startPage(Property.ofValue(0)).build(), "startPage");
        assertViolation(FromImages.builder().id("images").type(FromImages.class.getName()).images(Property.ofValue(List.of("kestra:///a.png"))).margin(Property.ofValue(-1f)).build(), "margin");
    }

    private void assertViolation(Object task, String property) {
        var violation = modelValidator.isValid(task);
        assertThat(violation.isPresent(), is(true));
        assertThat(violation.get().getMessage(), containsString(property));
    }
}
