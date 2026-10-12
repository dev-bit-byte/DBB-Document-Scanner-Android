# DBB PDF Workspace — Android v0.2 prototype

An offline visual PDF page organizer to prepare CGPSC PYQ papers.

Features:
- Import multiple PDFs using Android document picker (no storage permission required).
- See page thumbnails; tap to select, tap again to unselect (including from full-page preview).
- Long-press a page for actions and full-page preview.
- Remove one imported PDF and its selected pages, or reset all imported PDFs and workspace state.
- Type the examination date as DD/MM/YYYY or DDMMYYYY (with real calendar-date validation).
- Add all missing pages from a source PDF without duplicating already selected pages.
- Rearrange final pages with up/down controls, remove pages, clear sequence.
- CGPSC Smart Rename form (SSE default; year; set A/B/C/D; date; MA or AMA with AMA default).
- Export merged/reordered pages as a new PDF using PdfBox-Android without deliberate rasterization.
- Dark and light appearance and DBB's sky-blue/yellow theme.

Original input PDFs are not modified. PDFBox may not preserve every annotation, form, or advanced document feature; verify representative CGPSC exam PDFs before production use.

## APK build
On the `pdf-workspace-apk` branch, the PDF Workspace GitHub Actions workflow runs `gradle assembleDebug`. Download the `DBB-PDF-Workspace-v0.2-debug` artifact from GitHub Actions.

This is an isolated development branch inside the existing scanner repository to avoid changing the working scanner. Move the source to a dedicated PDF Workspace repository when available.
