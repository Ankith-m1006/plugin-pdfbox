package io.kestra.plugin.pdfbox.form;

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
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fill the form fields of a PDF",
    description = "Sets AcroForm fields by their fully qualified name and stores the filled PDF in internal storage. Check boxes accept `true`/`false` or the name of their on state. Unknown field names fail the task unless `ignoreUnknownFields` is true. With `flatten`, the fields are merged into the page content so the values can no longer be edited."
)
@Plugin(
    examples = {
        @Example(
            title = "Fill and flatten an onboarding form",
            full = true,
            code = """
                id: pdf_fill_form
                namespace: company.team

                inputs:
                  - id: form
                    type: FILE

                tasks:
                  - id: fill
                    type: io.kestra.plugin.pdfbox.form.Fill
                    from: "{{ inputs.form }}"
                    fields:
                      full_name: "Jane Doe"
                      start_date: "2026-11-02"
                      remote: "true"
                    flatten: true
                """
        )
    }
)
public class Fill extends AbstractPdfboxTask implements RunnableTask<Fill.Output> {
    @Schema(title = "The PDF form, as an internal storage URI")
    @NotNull
    @PluginProperty(internalStorageURI = true, group = "source")
    private Property<String> from;

    @Schema(
        title = "Owner password of an encrypted PDF",
        description = "The filled form is written unencrypted, so the user password alone is refused."
    )
    @PluginProperty(secret = true, group = "source")
    @ToString.Exclude
    private Property<String> password;

    @Schema(title = "Field values, keyed by fully qualified field name")
    @NotNull
    private Property<Map<String, String>> fields;

    @Schema(title = "Flatten the form after filling so the values become static page content")
    @Builder.Default
    private Property<Boolean> flatten = Property.ofValue(false);

    @Schema(title = "Skip field names that do not exist in the form instead of failing")
    @Builder.Default
    private Property<Boolean> ignoreUnknownFields = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Map<String, String> values = runContext.render(fields).asMap(String.class, String.class);
        boolean ignoreUnknown = runContext.render(ignoreUnknownFields).as(Boolean.class).orElse(false);

        try (PDDocument document = load(runContext, from, password)) {
            requireOwnerPermission(document, "form.Fill");
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            if (form == null) {
                throw new IllegalArgumentException("The PDF has no form fields to fill.");
            }
            if (form.hasXFA()) {
                // Hybrid forms carry an XFA copy of the values that Acrobat prefers over the AcroForm fields.
                // Dropping it makes every reader show the values filled here.
                form.setXFA(null);
                runContext.logger().info("Removed the XFA layer of the hybrid form so readers show the filled AcroForm values.");
            }

            List<String> unknown = new ArrayList<>();
            int filled = 0;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                PDField field = form.getField(entry.getKey());
                if (field == null) {
                    unknown.add(entry.getKey());
                    continue;
                }
                setValue(field, entry.getValue());
                filled++;
            }
            if (!unknown.isEmpty() && !ignoreUnknown) {
                throw new IllegalArgumentException(
                    "Unknown form fields %s. Available fields: %s.".formatted(unknown, fieldNames(form))
                );
            }
            if (!unknown.isEmpty()) {
                runContext.logger().warn("Skipped unknown form fields {}.", unknown);
            }

            if (runContext.render(flatten).as(Boolean.class).orElse(false)) {
                form.flatten();
            }
            return Output.builder().uri(save(runContext, document)).filledFields(filled).build();
        }
    }

    private static void setValue(PDField field, String value) throws Exception {
        // A null value (for example an empty YAML value) clears the field.
        if (value == null) {
            value = "";
        }
        if (field instanceof PDCheckBox checkBox) {
            if ("true".equalsIgnoreCase(value) || value.equals(checkBox.getOnValue())) {
                checkBox.check();
            } else if ("false".equalsIgnoreCase(value) || value.isEmpty()) {
                checkBox.unCheck();
            } else {
                throw new IllegalArgumentException(
                    "Check box '%s' accepts true, false or '%s', got '%s'.".formatted(field.getFullyQualifiedName(), checkBox.getOnValue(), value)
                );
            }
            return;
        }
        field.setValue(value);
    }

    private static List<String> fieldNames(PDAcroForm form) {
        List<String> names = new ArrayList<>();
        form.getFieldTree().forEach(field -> names.add(field.getFullyQualifiedName()));
        return names;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Internal storage URI of the filled PDF")
        private final URI uri;

        @Schema(title = "Number of fields that were set")
        private final Integer filledFields;
    }
}
