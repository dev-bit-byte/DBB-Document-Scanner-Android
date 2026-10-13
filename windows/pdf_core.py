"""Non-destructive PDF page editing and CGPSC filename helpers.

No GUI imports here, so the PDF engine can be unit-tested independently.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable
import re

import fitz


@dataclass(frozen=True)
class PageRef:
    source_id: str
    page_index: int


def normalize_exam_date(raw: str) -> str:
    """Accept DD/MM/YYYY, DD-MM-YYYY, DD_MM_YYYY or DDMMYYYY."""
    digits = re.sub(r"\D", "", raw.strip())
    if len(digits) != 8:
        raise ValueError("Enter the date as DD/MM/YYYY, for example 22/02/2026.")
    try:
        parsed = datetime.strptime(digits, "%d%m%Y")
    except ValueError as exc:
        raise ValueError("The examination date is invalid.") from exc
    return parsed.strftime("%d_%m_%Y")


def build_filename(exam: str, year: str, paper_set: str, date: str, answer: str) -> str:
    exam_code = re.sub(r"[^A-Z0-9]+", "_", exam.strip().upper()).strip("_")
    year = year.strip()
    paper_set = re.sub(r"[^A-Z0-9]+", "", paper_set.strip().upper())
    answer = answer.strip().upper()
    if not exam_code:
        raise ValueError("Enter an exam name or code.")
    if not re.fullmatch(r"\d{4}", year):
        raise ValueError("Exam year must contain four digits.")
    if not re.fullmatch(r"[A-Z0-9]{1,4}", paper_set):
        raise ValueError("Set must be 1–4 letters or numbers.")
    if answer not in ("MA", "AMA"):
        raise ValueError("Choose MA or AMA.")
    return f"CGPSC_{exam_code}_{year}_SET_{paper_set}_DATE_{normalize_exam_date(date)}_{answer}.pdf"


def count_pages(path: str | Path) -> int:
    with fitz.open(str(path)) as doc:
        if doc.needs_pass:
            raise ValueError("Password-protected PDFs are not supported yet.")
        if doc.page_count == 0:
            raise ValueError("The PDF has no pages.")
        return doc.page_count


def export_pdf(
    sources: dict[str, str | Path],
    pages: Iterable[PageRef],
    destination: str | Path,
) -> int:
    """Copy original PDF pages in requested order, without rasterizing.

    Writes to a temporary file first and atomically replaces the destination
    only on success. Original source PDFs are never opened for writing.
    """
    ordered = list(pages)
    if not ordered:
        raise ValueError("Select at least one page before saving.")
    target = Path(destination).expanduser().resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    source_paths = {key: Path(path).expanduser().resolve() for key, path in sources.items()}
    if target in source_paths.values():
        raise ValueError("Choose a new filename; overwriting an imported source PDF is not allowed.")

    import os
    import tempfile

    handle, tmp_name = tempfile.mkstemp(prefix=".dbb_pdf_", suffix=".pdf", dir=target.parent)
    os.close(handle)
    opened: dict[str, fitz.Document] = {}
    try:
        with fitz.open() as result:
            for ref in ordered:
                if ref.source_id not in source_paths:
                    raise ValueError("A selected page refers to a removed PDF.")
                if ref.source_id not in opened:
                    doc = fitz.open(str(source_paths[ref.source_id]))
                    if doc.needs_pass:
                        doc.close()
                        raise ValueError("Password-protected PDFs are not supported yet.")
                    opened[ref.source_id] = doc
                source_doc = opened[ref.source_id]
                if not 0 <= ref.page_index < source_doc.page_count:
                    raise ValueError("A selected page number is out of range.")
                result.insert_pdf(
                    source_doc,
                    from_page=ref.page_index,
                    to_page=ref.page_index,
                    links=True,
                    annots=True,
                )
            result.set_metadata({
                "title": target.stem,
                "creator": "DBB PDF Workspace",
                "producer": "PyMuPDF",
            })
            result.save(tmp_name, garbage=3, deflate=True)
        os.replace(tmp_name, target)
        return len(ordered)
    finally:
        for doc in opened.values():
            doc.close()
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
