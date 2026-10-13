# DBB PDF Workspace — Windows v0.1

Offline PDF page organizer for CGPSC previous-year question papers and model answer keys.

## Main workflow

1. **Add PDFs**: import one or more question papers and model answer PDFs.
2. **Select pages**: click a thumbnail to select it, click again to unselect it. Selected pages appear in the final sequence.
3. **Preview**: double-click or right-click a page for large view. Select/unselect in the preview; zoom and move between pages.
4. **Arrange**: drag pages in the Final PDF pane or use the Up/Down buttons. You can also remove individual final pages.
5. **Smart Rename**: choose exam (SSE by default), year, set, type date (DD/MM/YYYY), choose AMA (default) or MA.
6. **Save PDF**: export the final sequence as a new PDF without converting its text to images.

**Clear Selection** clears only final selected pages. **Remove selected PDF** removes one imported source and its selected pages. **Reset Workspace** clears all imported PDFs, selected pages and filename. None of these operations changes or deletes your original files.

Example filename: `CGPSC_SSE_2025_SET_A_DATE_22_02_2026_AMA.pdf`

## Run from Python

On Windows, install Python 3.11+:

```powershell
python -m pip install -r windows/requirements.txt
python windows/app.py
```

## Build EXE

The GitHub Actions workflow `.github/workflows/pdf-workspace-windows.yml` compiles a standalone Windows EXE using the Windows runner. Download `DBB-PDF-Workspace-Windows-v0.1` from the Actions build artifact.

For a local build on Windows:

```powershell
python -m PyInstaller --noconfirm --clean --onefile --windowed --name "DBB PDF Workspace" --collect-all pymupdf windows/app.py
```

The EXE is saved to `dist/DBB PDF Workspace.exe`.

## Limitations

- Password-protected PDFs are not yet supported.
- Copying pages preserves searchable text and typical annotations, but some advanced interactive form fields, document-level bookmarks and signatures may not survive page extraction.
- Unsigned EXE builds can trigger Microsoft Defender SmartScreen; verify the file source and use caution.
- PDF files are processed locally; the app does not upload their contents.
- This source currently lives in a separate development branch of the DBB scanner repository; the scanner's main branch remains unchanged.
