"""Regression tests for DBB PDF Workspace's PDF and filename engine."""
import tempfile
import unittest
from pathlib import Path
import sys

import fitz

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pdf_core import PageRef, build_filename, count_pages, export_pdf, normalize_exam_date


def make_pdf(path: Path, texts: list[str]) -> None:
    with fitz.open() as pdf:
        for text in texts:
            page = pdf.new_page()
            page.insert_text((72, 72), text)
        pdf.save(str(path))


class PdfCoreTests(unittest.TestCase):
    def test_filename_example(self):
        expected = "CGPSC_SSE_2025_SET_A_DATE_22_02_2026_AMA.pdf"
        self.assertEqual(
            build_filename("SSE", "2025", "A", "22/02/2026", "AMA"), expected)
        self.assertEqual(
            build_filename("sse", "2025", "a", "22022026", "ama"), expected)

    def test_date_formats(self):
        for raw in ("22/02/2026", "22-02-2026", "22_02_2026", "22022026"):
            self.assertEqual(normalize_exam_date(raw), "22_02_2026")
        for raw in ("31/02/2026", "22/13/2026", "1/2/26", ""):
            with self.assertRaises(ValueError):
                normalize_exam_date(raw)

    def test_invalid_fields(self):
        for fields in [
            ("", "2025", "A", "22/02/2026", "AMA"),
            ("SSE", "25", "A", "22/02/2026", "AMA"),
            ("SSE", "2025", "?", "22/02/2026", "AMA"),
            ("SSE", "2025", "A", "22/02/2026", "NONE"),
        ]:
            with self.assertRaises(ValueError):
                build_filename(*fields)

    def test_page_reordering_and_searchable_text(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            question = directory / "question.pdf"
            answer = directory / "answer.pdf"
            final = directory / "final.pdf"
            make_pdf(question, ["Question 1", "Question 2", "Question 3"])
            make_pdf(answer, ["Answer A", "Answer B"])
            refs = [
                PageRef("q", 0), PageRef("q", 2), PageRef("a", 1),
            ]
            n = export_pdf({"q": question, "a": answer}, refs, final)
            self.assertEqual(n, 3)
            self.assertEqual(count_pages(final), 3)
            with fitz.open(final) as pdf:
                self.assertIn("Question 1", pdf[0].get_text())
                self.assertIn("Question 3", pdf[1].get_text())
                self.assertIn("Answer B", pdf[2].get_text())
            self.assertEqual(count_pages(question), 3)
            self.assertEqual(count_pages(answer), 2)

    def test_never_overwrite_imported_source(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "input.pdf"
            make_pdf(path, ["Keep me"])
            with self.assertRaisesRegex(ValueError, "overwriting"):
                export_pdf({"q": path}, [PageRef("q", 0)], path)
            self.assertEqual(count_pages(path), 1)

    def test_empty_selection_and_missing_source(self):
        with tempfile.TemporaryDirectory() as folder:
            dest = Path(folder) / "out.pdf"
            with self.assertRaises(ValueError):
                export_pdf({}, [], dest)
            with self.assertRaises(ValueError):
                export_pdf({}, [PageRef("gone", 0)], dest)
            self.assertFalse(dest.exists())


if __name__ == "__main__":
    unittest.main()
