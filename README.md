# DBB PDF Workspace — Android prototype

An offline visual PDF page organizer to prepare CGPSC PYQ papers.

Features:
- Import multiple PDFs using Android document picker (no storage permission required).
- See page thumbnails, append one page or all pages to a new document.
- Rearrange final pages with up/down controls, remove pages, clear sequence.
- CGPSC Smart Rename form (SSE default; year; set A/B/C/D; date; MA or AMA with AMA default).
- Export merged/reordered pages as a new PDF using PdfBox-Android without deliberate rasterization.
- Dark and light appearance and DBB's sky-blue/yellow theme.

Original input PDFs are not modified. PDFBox may not preserve every annotation, form, or advanced document feature; verify representative CGPSC exam PDFs before production use.

## APK build
On the `pdf-workspace-apk` branch, the PDF Workspace GitHub Actions workflow runs `gradle assembleDebug`. Download the `DBB-PDF-Workspace-v0.1-debug` artifact from GitHub Actions.

This is an isolated development branch inside the existing scanner repository to avoid changing the working scanner. Move the source to a dedicated PDF Workspace repository when available.
