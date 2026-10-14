# How to use the Apache PDFBox plugin

Create, read, split, merge, secure and render PDF files from Kestra flows with [Apache PDFBox](https://pdfbox.apache.org/). Everything runs inside the worker JVM: no container, no external service, and every input and output is a Kestra internal storage URI (`kestra://...`).

## Tasks

- `text.Extract` returns the text layer of a PDF for a page range (`startPage`, `endPage`), and page by page with `perPage: true`. Scanned PDFs have no text layer; render them with `image.Render` and run OCR instead.
- `image.Render` renders pages to PNG or JPEG at a given `dpi` (36 to 600). At most `maxPages` pages (default 100) are rendered per run.
- `image.Extract` saves the images embedded in the pages as PNG files. It stops after `maxImages` (default 500) and skips images larger than `maxImagePixels`.
- `document.Merge` appends several PDFs (`files`) into one, in order.
- `document.Split` splits a PDF by `ranges` (`"1"`, `"3-7"`) or into chunks of `everyNPages`.
- `document.FromImages` builds a PDF with one image per page, scaled to fit a page size (`A4`, `LETTER`, ...) or sized to the image (`IMAGE`).
- `document.FromHtml` renders well-formed XHTML to PDF. Page margins and breaks come from CSS (`@page { margin: 20mm }`).
- `metadata.Read` and `metadata.Update` read or set the title, author, subject, keywords, creator and custom keys.
- `security.Encrypt` protects a PDF with a user and an owner password (AES-256 by default) and permission flags; `security.Decrypt` removes the protection with the owner password.
- `form.Fill` sets AcroForm fields by name and can `flatten` them into the page.

Tasks that change a PDF return its `uri`; tasks that produce several files return `uris`.

## Encrypted PDFs

Tasks that read a PDF accept a `password`. Pass it with `{{ secret('...') }}`: password properties are secret and never logged. A wrong or missing password fails the task with a clear message.

## Resources in HTML

`document.FromHtml` loads images, stylesheets and fonts only from inline `data:` URIs and Kestra internal storage (`kestra://` URIs, or paths relative to `baseUri`). Remote `http` and `https` resources are blocked unless `allowRemoteResources: true`, and are then fetched with Kestra's HTTP client. `file:` URIs are always blocked.

## Limits

Very large or hostile PDFs are bounded by `maxPages` (`image.Render`), `maxImages` and `maxImagePixels` (`image.Extract`) and the DPI cap. Raise them deliberately for big documents.
