# Kestra Apache PDFBox Plugin

## What

- Provides PDF tasks under `io.kestra.plugin.pdfbox`, built on Apache PDFBox 3 (and OpenHTMLtoPDF for `document.FromHtml`).
- Sub-packages: `text` (`Extract`), `image` (`Extract`, `Render`), `document` (`Merge`, `Split`, `FromImages`, `FromHtml`), `metadata` (`Read`, `Update`), `security` (`Encrypt`, `Decrypt`), `form` (`Fill`).

## Why

- What user problem does this solve? Document pipelines need to extract, merge, split, protect, fill and render PDFs, which otherwise means shelling out to Python or `pdftk` in a script task.
- Why would a team adopt this plugin in a workflow? The tasks run in the worker JVM with no container and read and write Kestra internal storage, next to the AI, OCR and storage tasks of the same flow.
- What operational/business outcome does it enable? Invoice, report and contract workflows stay declarative, with native outputs, retries and audit history instead of custom scripts.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.pdfbox`: `text`, `image`, `document`, `metadata`, `security`, `form`, plus `AbstractPdfboxTask` with the shared load and save helpers.

No external service is needed; tests build their fixture PDFs in code.

### Key Plugin Classes

- `io.kestra.plugin.pdfbox.AbstractPdfboxTask`: downloads inputs from internal storage, opens them with `Loader.loadPDF` (with password handling and clear errors), and stores outputs.
- One task class per operation, named by action (`Extract`, `Render`, `Merge`, ...) without repeating the package name.

### Project Structure

```
plugin-pdfbox/
├── src/main/java/io/kestra/plugin/pdfbox/
├── src/test/java/io/kestra/plugin/pdfbox/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Open every `PDDocument` in try-with-resources and never keep one on the task instance; tasks stay stateless.
- Large outputs (pages, images, split files) go to internal storage and are returned as URIs.
- Keep the documented bounds (`maxPages`, `maxImages`, `maxImagePixels`, the DPI cap) when adding tasks that can expand a hostile PDF.
- `document.FromHtml` must not fetch remote resources unless `allowRemoteResources` is set, and then only through Kestra's `HttpClient`; `file:` stays blocked.
- Password properties use `@PluginProperty(secret = true)` and `@ToString.Exclude`.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
