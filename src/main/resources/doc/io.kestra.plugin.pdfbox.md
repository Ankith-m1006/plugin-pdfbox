# How to use the Apache PDFBox plugin

Create, read, split, merge, secure and render PDF files from Kestra flows with [Apache PDFBox](https://pdfbox.apache.org/). Everything runs inside the worker JVM: no container, no external service, and every input and output is a Kestra internal storage URI (`kestra://...`).

## Tasks

- `text.Extract` returns the text layer of a PDF for a page range (`startPage`, `endPage`), and page by page with `perPage: true`. Scanned PDFs have no text layer; render them with `image.Render` and run OCR instead.
- `image.Render` renders pages to PNG or JPEG at a given `dpi` (36 to 600). At most `maxPages` pages (default 100) are rendered per run, and a page larger than `maxPagePixels` (default 40 million pixels at the chosen DPI) fails before rendering.
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

`text.Extract`, `image.Render`, `image.Extract`, `metadata.Read`, `document.Split`, `metadata.Update`, `form.Fill` and `security.Decrypt` accept a `password`. Pass it with `{{ secret('...') }}`: password properties are secret and never logged. A wrong or missing password fails the task with a clear message.

The tasks that only read (`text.Extract`, `image.Render`, `image.Extract`, `metadata.Read`) work with the user password. `document.Split`, `metadata.Update`, `form.Fill` and `security.Decrypt` write an unencrypted copy, so they need the owner password and refuse the user password alone; protect the result again with `security.Encrypt` if needed. `document.Merge` and `security.Encrypt` do not take a password: they refuse encrypted inputs, so decrypt them first.

## Resources in HTML

`document.FromHtml` loads images, stylesheets and fonts only from inline `data:` URIs and Kestra internal storage (`kestra://` URIs, or paths relative to `baseUri`). Remote `http` and `https` resources are blocked unless `allowRemoteResources: true`, and are then fetched with Kestra's HTTP client. `file:` URIs are always blocked. A resource that cannot be loaded is skipped with a warning naming its URL, and the HTML file and every resource are limited to `maxResourceBytes` (20 MB by default).

## Limits

Very large or hostile inputs are bounded before they can exhaust the worker. Raise the limits deliberately for big documents.

| Task | Limits (default) |
|---|---|
| `text.Extract` | `maxPages` (1000) |
| `image.Render` | `maxPages` (100), `maxPagePixels` (40 million), `dpi` 36 to 600 |
| `image.Extract` | `maxImages` (500), `maxImagePixels` (40 million) |
| `document.Merge` | `maxFiles` (100), `maxPages` of the result (10000) |
| `document.Split` | `maxParts` (1000) |
| `document.FromImages` | `maxImages` (500), `maxImagePixels` (40 million, read from the image header before decoding) |
| `document.FromHtml` | `maxResourceBytes` (20 MB) for the HTML file and each resource |

An empty PDF, or a `startPage` past the last page, fails with a message that says so.
