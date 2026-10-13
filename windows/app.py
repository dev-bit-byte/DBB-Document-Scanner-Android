"""DBB PDF Workspace — Windows desktop application.

Run: python windows/app.py
Build: pyinstaller --onefile --windowed --name "DBB PDF Workspace" windows/app.py
"""
from __future__ import annotations

import sys
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import fitz
from PySide6.QtCore import Qt, QSize, QTimer
from PySide6.QtGui import QColor, QFont, QIcon, QImage, QPixmap
from PySide6.QtWidgets import (
    QApplication, QAbstractItemView, QComboBox, QDialog, QFileDialog, QFrame,
    QHBoxLayout, QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow,
    QMenu, QMessageBox, QPushButton, QScrollArea, QSplitter, QStatusBar,
    QVBoxLayout, QWidget,
)

from pdf_core import PageRef, build_filename, count_pages, export_pdf


SKY = "#58BFEF"
NAVY = "#15334E"
YELLOW = "#FFDA55"


@dataclass
class Source:
    id: str
    path: Path
    count: int

    @property
    def name(self) -> str:
        return self.path.name


class ThumbnailCache:
    """Small LRU-ish pixmap cache; never alters the PDF being rendered."""

    def __init__(self, max_items: int = 180):
        self.items: dict[tuple[str, int, int], QPixmap] = {}
        self.order: list[tuple[str, int, int]] = []
        self.max_items = max_items

    def get(self, source: Source, page_index: int, width: int = 140) -> QPixmap:
        key = (source.id, page_index, width)
        if key in self.items:
            return self.items[key]
        with fitz.open(str(source.path)) as doc:
            page = doc.load_page(page_index)
            scale = min(width / page.rect.width, 3.0)
            pix = page.get_pixmap(matrix=fitz.Matrix(scale, scale),
                                  colorspace=fitz.csRGB, alpha=False)
            image = QImage(pix.samples, pix.width, pix.height, pix.stride,
                           QImage.Format.Format_RGB888).copy()
        result = QPixmap.fromImage(image)
        self.items[key] = result
        self.order.append(key)
        if len(self.order) > self.max_items:
            stale = self.order.pop(0)
            self.items.pop(stale, None)
        return result

    def discard(self, source_id: str) -> None:
        self.order = [key for key in self.order if key[0] != source_id]
        self.items = {key: value for key, value in self.items.items()
                      if key[0] != source_id}


class SmartRenameDialog(QDialog):
    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setWindowTitle("CGPSC Smart Rename")
        self.setMinimumWidth(460)
        self.generated: str | None = None
        layout = QVBoxLayout(self)
        layout.setContentsMargins(22, 20, 22, 20)
        layout.setSpacing(12)

        title = QLabel("Create a consistent CGPSC filename")
        title.setObjectName("Heading")
        layout.addWidget(title)

        self.exam = QComboBox()
        self.exam.setEditable(True)
        self.exam.addItems(["SSE", "ACF", "FSO", "AE", "ADP", "OTHER"])
        self.exam.setCurrentText("SSE")
        layout.addWidget(self._field("Exam code (SSE by default)", self.exam))

        self.year = QLineEdit()
        self.year.setPlaceholderText("e.g. 2025")
        self.year.setMaxLength(4)
        layout.addWidget(self._field("Exam year", self.year))

        self.paper_set = QComboBox()
        self.paper_set.setEditable(True)
        self.paper_set.addItems(["A", "B", "C", "D"])
        layout.addWidget(self._field("Paper set", self.paper_set))

        self.date = QLineEdit()
        self.date.setPlaceholderText("22/02/2026")
        self.date.setMaxLength(12)
        layout.addWidget(self._field("Examination date (DD/MM/YYYY)", self.date))

        self.answer = QComboBox()
        self.answer.addItems(["AMA", "MA"])
        layout.addWidget(self._field("Answer-key type", self.answer))

        hint = QLabel("AMA = amended model answer  •  MA = model answer")
        hint.setObjectName("Hint")
        layout.addWidget(hint)
        self.preview = QLabel("Complete the fields to preview the filename")
        self.preview.setObjectName("FilePreview")
        self.preview.setWordWrap(True)
        self.preview.setMinimumHeight(70)
        layout.addWidget(self.preview)

        row = QHBoxLayout()
        cancel = QPushButton("Cancel")
        cancel.setObjectName("OutlineButton")
        cancel.clicked.connect(self.reject)
        use = QPushButton("Use filename")
        use.setObjectName("YellowButton")
        use.clicked.connect(self.apply)
        row.addStretch()
        row.addWidget(cancel)
        row.addWidget(use)
        layout.addLayout(row)

        for widget in (self.year, self.date):
            widget.textChanged.connect(self.update_preview)
        for widget in (self.exam, self.paper_set, self.answer):
            widget.currentTextChanged.connect(self.update_preview)
        if self.exam.lineEdit():
            self.exam.lineEdit().textChanged.connect(self.update_preview)
        if self.paper_set.lineEdit():
            self.paper_set.lineEdit().textChanged.connect(self.update_preview)
        self.update_preview()

    @staticmethod
    def _field(label: str, widget: QWidget) -> QWidget:
        frame = QWidget()
        column = QVBoxLayout(frame)
        column.setContentsMargins(0, 0, 0, 0)
        column.setSpacing(5)
        text = QLabel(label)
        text.setObjectName("FieldLabel")
        column.addWidget(text)
        column.addWidget(widget)
        return frame

    def filename(self) -> str:
        return build_filename(
            self.exam.currentText(), self.year.text(),
            self.paper_set.currentText(), self.date.text(),
            self.answer.currentText(),
        )

    def update_preview(self, *_: object) -> None:
        try:
            self.preview.setText(self.filename())
        except ValueError:
            self.preview.setText("CGPSC_SSE_2025_SET_A_DATE_22_02_2026_AMA.pdf")

    def apply(self) -> None:
        try:
            self.generated = self.filename()
        except ValueError as exc:
            QMessageBox.warning(self, "Check filename details", str(exc))
            return
        self.accept()


class PagePreviewDialog(QDialog):
    def __init__(self, parent: QWidget, source: Source, page_index: int,
                 is_selected, toggle):
        super().__init__(parent)
        self.source = source
        self.page_index = page_index
        self.is_selected = is_selected
        self.toggle = toggle
        self.zoom = 1.2
        self.setWindowTitle(f"Page preview — {source.name}")
        self.resize(860, 780)
        self.setMinimumSize(580, 500)

        root = QVBoxLayout(self)
        header = QHBoxLayout()
        self.title = QLabel()
        self.title.setObjectName("Heading")
        header.addWidget(self.title)
        header.addStretch()
        for text, fn in [("− Zoom", self.zoom_out), ("+ Zoom", self.zoom_in)]:
            btn = QPushButton(text)
            btn.setObjectName("OutlineButton")
            btn.clicked.connect(fn)
            header.addWidget(btn)
        root.addLayout(header)

        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setAlignment(Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignVCenter)
        self.image = QLabel()
        self.image.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.scroll.setWidget(self.image)
        root.addWidget(self.scroll, 1)

        footer = QHBoxLayout()
        prev = QPushButton("◀ Previous")
        prev.setObjectName("OutlineButton")
        prev.clicked.connect(lambda: self.change_page(-1))
        nxt = QPushButton("Next ▶")
        nxt.setObjectName("OutlineButton")
        nxt.clicked.connect(lambda: self.change_page(1))
        self.selection_btn = QPushButton()
        self.selection_btn.setObjectName("YellowButton")
        self.selection_btn.clicked.connect(self.toggle_current)
        close = QPushButton("Close")
        close.setObjectName("OutlineButton")
        close.clicked.connect(self.accept)
        footer.addWidget(prev)
        footer.addWidget(nxt)
        footer.addStretch()
        footer.addWidget(self.selection_btn)
        footer.addWidget(close)
        root.addLayout(footer)
        self.render_page()

    def render_page(self) -> None:
        self.title.setText(f"Page {self.page_index + 1} of {self.source.count}")
        try:
            with fitz.open(str(self.source.path)) as doc:
                pix = doc.load_page(self.page_index).get_pixmap(
                    matrix=fitz.Matrix(self.zoom, self.zoom),
                    colorspace=fitz.csRGB, alpha=False)
                image = QImage(pix.samples, pix.width, pix.height, pix.stride,
                               QImage.Format.Format_RGB888).copy()
            self.image.setPixmap(QPixmap.fromImage(image))
        except Exception as exc:
            self.image.setText(f"Could not render this page: {exc}")
        self.refresh_selection()

    def refresh_selection(self) -> None:
        picked = self.is_selected(self.source.id, self.page_index)
        self.selection_btn.setText("✓ Selected — click to unselect" if picked
                                   else "Select this page")

    def toggle_current(self) -> None:
        self.toggle(self.source.id, self.page_index)
        self.refresh_selection()

    def change_page(self, delta: int) -> None:
        new_index = self.page_index + delta
        if 0 <= new_index < self.source.count:
            self.page_index = new_index
            self.render_page()

    def zoom_in(self) -> None:
        self.zoom = min(3.0, round(self.zoom + 0.25, 2))
        self.render_page()

    def zoom_out(self) -> None:
        self.zoom = max(0.5, round(self.zoom - 0.25, 2))
        self.render_page()


class Workspace(QMainWindow):
    def __init__(self):
        super().__init__()
        self.sources: dict[str, Source] = {}
        self.picks: list[PageRef] = []
        self.selected_source_id: str | None = None
        self.filename: str = ""
        self.thumbs = ThumbnailCache()
        self.render_token = 0

        self.setWindowTitle("DBB PDF Workspace — v0.1 Windows")
        self.resize(1380, 850)
        self.setMinimumSize(980, 610)
        self._setup_ui()
        self.setStyleSheet(STYLESHEET)
        self.update_counts()

    def _setup_ui(self) -> None:
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(18, 14, 18, 14)
        root.setSpacing(12)

        header = QFrame()
        header.setObjectName("Header")
        bar = QHBoxLayout(header)
        bar.setContentsMargins(16, 12, 16, 12)
        brand = QLabel("DBB")
        brand.setObjectName("Brand")
        bar.addWidget(brand)
        heading = QVBoxLayout()
        title = QLabel("PDF Workspace")
        title.setObjectName("HeaderTitle")
        subtitle = QLabel("Prepare, rearrange and combine CGPSC PDF pages")
        subtitle.setObjectName("HeaderSubtitle")
        heading.addWidget(title)
        heading.addWidget(subtitle)
        bar.addLayout(heading)
        bar.addStretch()
        version = QLabel("WINDOWS  •  v0.1")
        version.setObjectName("HeaderSubtitle")
        bar.addWidget(version)
        root.addWidget(header)

        toolbar = QHBoxLayout()
        toolbar.setSpacing(9)
        self.add_btn = self._button("+  Add PDFs", "YellowButton", self.add_pdfs)
        self.rename_btn = self._button("Smart Rename", "PrimaryButton", self.smart_rename)
        self.export_btn = self._button("Save PDF", "PrimaryButton", self.save_pdf)
        self.clear_btn = self._button("Clear Selection", "OutlineButton", self.clear_selection)
        self.reset_btn = self._button("Reset Workspace", "DangerOutlineButton", self.reset_workspace)
        for btn in (self.add_btn, self.rename_btn, self.export_btn):
            toolbar.addWidget(btn)
        toolbar.addStretch()
        toolbar.addWidget(self.clear_btn)
        toolbar.addWidget(self.reset_btn)
        root.addLayout(toolbar)

        self.filename_label = QLabel("Filename: DBB_PDF_Workspace.pdf")
        self.filename_label.setObjectName("Filename")
        self.filename_label.setWordWrap(True)
        root.addWidget(self.filename_label)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.setChildrenCollapsible(False)
        splitter.setHandleWidth(10)
        root.addWidget(splitter, 1)

        # Left: imported source PDFs.
        left = self._panel()
        left.setMinimumWidth(215)
        left_layout = left.layout()
        left_layout.addWidget(self._heading("Imported PDFs"))
        self.source_list = QListWidget()
        self.source_list.setObjectName("SourceList")
        self.source_list.currentItemChanged.connect(self.on_source_changed)
        left_layout.addWidget(self.source_list, 1)
        self.source_info = QLabel("No PDFs imported")
        self.source_info.setObjectName("Hint")
        left_layout.addWidget(self.source_info)
        left_layout.addWidget(self._button("Remove selected PDF", "DangerOutlineButton",
                                           self.remove_pdf))
        splitter.addWidget(left)

        # Center: page thumbnails for the active source PDF.
        middle = self._panel()
        middle.setMinimumWidth(390)
        middle_layout = middle.layout()
        mid_row = QHBoxLayout()
        self.current_title = self._heading("Pages")
        mid_row.addWidget(self.current_title, 1)
        mid_row.addWidget(self._button("Add all pages", "OutlineButton", self.add_all))
        middle_layout.addLayout(mid_row)
        self.instructions = QLabel("Click to select/unselect  •  Double-click to preview")
        self.instructions.setObjectName("Hint")
        middle_layout.addWidget(self.instructions)
        self.grid = QListWidget()
        self.grid.setObjectName("PageGrid")
        self.grid.setViewMode(QListWidget.ViewMode.IconMode)
        self.grid.setResizeMode(QListWidget.ResizeMode.Adjust)
        self.grid.setMovement(QListWidget.Movement.Static)
        self.grid.setSpacing(10)
        self.grid.setIconSize(QSize(138, 175))
        self.grid.setGridSize(QSize(164, 220))
        self.grid.setSelectionMode(QAbstractItemView.SelectionMode.NoSelection)
        self.grid.itemClicked.connect(self.toggle_grid_item)
        self.grid.itemDoubleClicked.connect(self.preview_grid_item)
        self.grid.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.grid.customContextMenuRequested.connect(self.grid_context_menu)
        middle_layout.addWidget(self.grid, 1)
        splitter.addWidget(middle)

        # Right: final ordered page list, drag-and-drop enabled.
        right = self._panel()
        right.setMinimumWidth(285)
        right_layout = right.layout()
        self.final_title = self._heading("Final PDF  ·  0 pages")
        right_layout.addWidget(self.final_title)
        drag_hint = QLabel("Drag to reorder pages, or use ↑ and ↓")
        drag_hint.setObjectName("Hint")
        right_layout.addWidget(drag_hint)
        self.final_list = QListWidget()
        self.final_list.setObjectName("FinalList")
        self.final_list.setIconSize(QSize(57, 76))
        self.final_list.setDragDropMode(QAbstractItemView.DragDropMode.InternalMove)
        self.final_list.setDefaultDropAction(Qt.DropAction.MoveAction)
        self.final_list.setDragEnabled(True)
        self.final_list.setAcceptDrops(True)
        self.final_list.setDropIndicatorShown(True)
        self.final_list.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.final_list.model().rowsMoved.connect(self.sync_final_order)
        self.final_list.itemDoubleClicked.connect(self.preview_final_item)
        right_layout.addWidget(self.final_list, 1)
        controls = QHBoxLayout()
        controls.addWidget(self._button("↑ Up", "OutlineButton", lambda: self.move_final(-1)))
        controls.addWidget(self._button("↓ Down", "OutlineButton", lambda: self.move_final(1)))
        controls.addWidget(self._button("Remove page", "DangerOutlineButton",
                                        self.remove_final_page))
        right_layout.addLayout(controls)
        splitter.addWidget(right)
        splitter.setSizes([250, 670, 370])

        self.setStatusBar(QStatusBar())
        self.statusBar().showMessage("Ready. Import a question paper and an answer-key PDF.")

    @staticmethod
    def _panel() -> QFrame:
        frame = QFrame()
        frame.setObjectName("Panel")
        layout = QVBoxLayout(frame)
        layout.setContentsMargins(13, 15, 13, 13)
        layout.setSpacing(9)
        return frame

    @staticmethod
    def _heading(text: str) -> QLabel:
        label = QLabel(text)
        label.setObjectName("Heading")
        return label

    @staticmethod
    def _button(text: str, style: str, callback) -> QPushButton:
        button = QPushButton(text)
        button.setObjectName(style)
        button.setCursor(Qt.CursorShape.PointingHandCursor)
        button.clicked.connect(callback)
        return button

    def add_pdfs(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(
            self, "Import PDF files", str(Path.home() / "Documents"),
            "PDF documents (*.pdf)")
        if not paths:
            return
        errors: list[str] = []
        existing = {s.path.resolve() for s in self.sources.values()}
        new_id: str | None = None
        for raw in paths:
            path = Path(raw).resolve()
            if path in existing:
                continue
            try:
                pages = count_pages(path)
                source = Source(str(uuid.uuid4()), path, pages)
                self.sources[source.id] = source
                item = QListWidgetItem(f"📄  {source.name}\n      {pages} pages")
                item.setData(Qt.ItemDataRole.UserRole, source.id)
                item.setToolTip(str(path))
                self.source_list.addItem(item)
                existing.add(path)
                if new_id is None:
                    new_id = source.id
            except Exception as exc:
                errors.append(f"{path.name}: {exc}")
        if new_id:
            for i in range(self.source_list.count()):
                item = self.source_list.item(i)
                if item.data(Qt.ItemDataRole.UserRole) == new_id:
                    self.source_list.setCurrentItem(item)
                    break
        self.update_counts()
        if errors:
            QMessageBox.warning(self, "Some PDFs could not be imported", "\n".join(errors))
        self.statusBar().showMessage(f"{len(self.sources)} PDF(s) loaded.")

    def on_source_changed(self, current: QListWidgetItem | None, _previous=None) -> None:
        source_id = current.data(Qt.ItemDataRole.UserRole) if current else None
        self.selected_source_id = source_id
        self.populate_grid()

    def populate_grid(self) -> None:
        self.render_token += 1
        token = self.render_token
        self.grid.clear()
        source = self.sources.get(self.selected_source_id or "")
        if not source:
            self.current_title.setText("Pages")
            return
        self.current_title.setText(f"Pages  ·  {source.count}")
        for page_index in range(source.count):
            item = QListWidgetItem(f"Page {page_index + 1}")
            item.setData(Qt.ItemDataRole.UserRole, page_index)
            item.setTextAlignment(Qt.AlignmentFlag.AlignHCenter)
            self.grid.addItem(item)
            self.style_grid_item(item, source.id, page_index)
        # Render thumbnails in small batches, keeping the interface responsive.
        def batch(start: int = 0) -> None:
            if token != self.render_token or self.selected_source_id != source.id:
                return
            for index in range(start, min(start + 3, source.count)):
                try:
                    pix = self.thumbs.get(source, index, 138)
                    self.grid.item(index).setIcon(QIcon(pix))
                except Exception:
                    self.grid.item(index).setText(f"Page {index + 1}\nPreview unavailable")
            if start + 3 < source.count:
                QTimer.singleShot(1, lambda: batch(start + 3))
        QTimer.singleShot(0, batch)

    def is_selected(self, source_id: str, page_index: int) -> bool:
        return PageRef(source_id, page_index) in self.picks

    def style_grid_item(self, item: QListWidgetItem, source_id: str, index: int) -> None:
        picked = self.is_selected(source_id, index)
        item.setText(f"Page {index + 1}" + ("  ✓" if picked else ""))
        item.setBackground(QColor("#FFF1AD" if picked else "#FFFFFF"))
        item.setForeground(QColor(NAVY))

    def refresh_grid_marks(self) -> None:
        if not self.selected_source_id:
            return
        for i in range(self.grid.count()):
            item = self.grid.item(i)
            index = item.data(Qt.ItemDataRole.UserRole)
            self.style_grid_item(item, self.selected_source_id, index)

    def toggle_page(self, source_id: str, page_index: int) -> None:
        ref = PageRef(source_id, page_index)
        if ref in self.picks:
            self.picks.remove(ref)
        else:
            self.picks.append(ref)
        self.refresh_final()
        self.refresh_grid_marks()

    def toggle_grid_item(self, item: QListWidgetItem) -> None:
        if self.selected_source_id:
            self.toggle_page(self.selected_source_id,
                             item.data(Qt.ItemDataRole.UserRole))

    def preview_grid_item(self, item: QListWidgetItem) -> None:
        source = self.sources.get(self.selected_source_id or "")
        if source:
            self.open_preview(source, item.data(Qt.ItemDataRole.UserRole))

    def grid_context_menu(self, point) -> None:
        item = self.grid.itemAt(point)
        source = self.sources.get(self.selected_source_id or "")
        if item is None or source is None:
            return
        index = item.data(Qt.ItemDataRole.UserRole)
        menu = QMenu(self)
        preview = menu.addAction("View large")
        toggle = menu.addAction("Unselect page" if self.is_selected(source.id, index)
                                else "Select page")
        action = menu.exec(self.grid.mapToGlobal(point))
        if action == preview:
            self.open_preview(source, index)
        elif action == toggle:
            self.toggle_page(source.id, index)

    def preview_final_item(self, item: QListWidgetItem) -> None:
        ref = item.data(Qt.ItemDataRole.UserRole)
        source = self.sources.get(ref.source_id)
        if source:
            self.open_preview(source, ref.page_index)

    def open_preview(self, source: Source, index: int) -> None:
        dialog = PagePreviewDialog(self, source, index, self.is_selected,
                                   self.toggle_page)
        dialog.exec()

    def add_all(self) -> None:
        source = self.sources.get(self.selected_source_id or "")
        if not source:
            return
        added = 0
        for index in range(source.count):
            ref = PageRef(source.id, index)
            if ref not in self.picks:
                self.picks.append(ref)
                added += 1
        self.refresh_final()
        self.refresh_grid_marks()
        self.statusBar().showMessage(f"Added {added} page(s) from {source.name}.")

    def refresh_final(self, focus: int | None = None) -> None:
        self.final_list.blockSignals(True)
        self.final_list.clear()
        for pos, ref in enumerate(self.picks):
            source = self.sources.get(ref.source_id)
            if source is None:
                continue
            item = QListWidgetItem(
                f"{pos + 1}.  Page {ref.page_index + 1}\n{source.name}")
            item.setData(Qt.ItemDataRole.UserRole, ref)
            item.setSizeHint(QSize(250, 90))
            try:
                item.setIcon(QIcon(self.thumbs.get(source, ref.page_index, 57)))
            except Exception:
                pass
            self.final_list.addItem(item)
        self.final_list.blockSignals(False)
        if focus is not None and 0 <= focus < self.final_list.count():
            self.final_list.setCurrentRow(focus)
        self.update_counts()

    def sync_final_order(self, *_: object) -> None:
        self.picks = [
            self.final_list.item(i).data(Qt.ItemDataRole.UserRole)
            for i in range(self.final_list.count())
        ]
        self.refresh_final()
        self.refresh_grid_marks()

    def move_final(self, delta: int) -> None:
        index = self.final_list.currentRow()
        new_index = index + delta
        if index < 0 or not 0 <= new_index < len(self.picks):
            return
        self.picks[index], self.picks[new_index] = self.picks[new_index], self.picks[index]
        self.refresh_final(new_index)

    def remove_final_page(self) -> None:
        index = self.final_list.currentRow()
        if index < 0:
            return
        self.picks.pop(index)
        self.refresh_final(min(index, len(self.picks) - 1))
        self.refresh_grid_marks()

    def remove_pdf(self) -> None:
        source = self.sources.get(self.selected_source_id or "")
        if source is None:
            return
        answer = QMessageBox.question(
            self, "Remove imported PDF?",
            f"Remove {source.name} and its selected pages from the workspace?\n\n"
            "The original PDF on your computer will not be deleted.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No)
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.picks = [ref for ref in self.picks if ref.source_id != source.id]
        self.thumbs.discard(source.id)
        del self.sources[source.id]
        row = self.source_list.currentRow()
        self.source_list.takeItem(row)
        self.refresh_final()
        self.update_counts()
        self.statusBar().showMessage(f"Removed {source.name} from workspace.")

    def clear_selection(self) -> None:
        if not self.picks:
            return
        self.picks.clear()
        self.refresh_final()
        self.refresh_grid_marks()
        self.statusBar().showMessage("Final page selection cleared; imported PDFs retained.")

    def reset_workspace(self) -> None:
        if not self.sources and not self.picks:
            return
        answer = QMessageBox.question(
            self, "Reset workspace?",
            "Remove all imported PDFs, selected pages and the prepared filename?\n\n"
            "Your original files will remain untouched.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No)
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.render_token += 1
        self.sources.clear()
        self.picks.clear()
        self.selected_source_id = None
        self.filename = ""
        self.thumbs = ThumbnailCache()
        self.source_list.clear()
        self.grid.clear()
        self.refresh_final()
        self.current_title.setText("Pages")
        self.filename_label.setText("Filename: DBB_PDF_Workspace.pdf")
        self.statusBar().showMessage("Workspace reset.")

    def smart_rename(self) -> None:
        dialog = SmartRenameDialog(self)
        if dialog.exec() == QDialog.DialogCode.Accepted and dialog.generated:
            self.filename = dialog.generated
            self.filename_label.setText(f"Filename: {self.filename}")
            self.statusBar().showMessage("CGPSC filename prepared. Save PDF to export.")

    def save_pdf(self) -> None:
        if not self.picks:
            QMessageBox.information(self, "No pages selected",
                                    "Select pages from one or more imported PDFs first.")
            return
        default_name = self.filename or "DBB_PDF_Workspace.pdf"
        suggested = Path.home() / "Documents" / default_name
        target, _ = QFileDialog.getSaveFileName(
            self, "Save combined PDF", str(suggested), "PDF documents (*.pdf)")
        if not target:
            return
        if not target.lower().endswith(".pdf"):
            target += ".pdf"
        if Path(target).exists():
            confirm = QMessageBox.question(
                self, "Replace existing PDF?",
                f"Replace the existing file?\n{target}",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No)
            if confirm != QMessageBox.StandardButton.Yes:
                return
        try:
            count = export_pdf(
                {key: src.path for key, src in self.sources.items()},
                self.picks, target)
        except Exception as exc:
            QMessageBox.critical(self, "Export failed", str(exc))
            return
        self.statusBar().showMessage(f"Saved {count} pages: {target}")
        QMessageBox.information(
            self, "PDF saved successfully",
            f"Created {Path(target).name}\n\nPages: {count}\nLocation: {target}")

    def update_counts(self) -> None:
        self.final_title.setText(f"Final PDF  ·  {len(self.picks)} pages")
        self.source_info.setText(
            f"{len(self.sources)} PDF(s) imported  ·  "
            f"{sum(s.count for s in self.sources.values())} source pages")
        self.export_btn.setEnabled(bool(self.picks))
        self.clear_btn.setEnabled(bool(self.picks))
        self.reset_btn.setEnabled(bool(self.sources))
        self.add_btn.setEnabled(True)


STYLESHEET = """
QMainWindow, QWidget { background: #F4F8FC; color: #15334E;
    font-family: 'Segoe UI'; font-size: 12px; }
QFrame#Header { background: #58BFEF; border-radius: 15px; }
QFrame#Header QLabel { background: transparent; }
QLabel#Brand { background: #FFDA55; color: #15334E; border-radius: 10px;
    font-size: 20px; font-weight: 900; padding: 10px 14px; }
QLabel#HeaderTitle { color: #15334E; font-size: 24px; font-weight: 800; }
QLabel#HeaderSubtitle { color: #194663; font-size: 12px; }
QFrame#Panel { background: white; border: 1px solid #E0E9F0; border-radius: 14px; }
QFrame#Panel QLabel, QFrame#Panel QWidget { background: transparent; }
QLabel#Heading { font-size: 17px; font-weight: 700; color: #15334E; }
QLabel#Hint { color: #6B7D8D; font-size: 11px; }
QLabel#Filename { background: #EAF4FC; color: #254763; padding: 8px 12px;
    border-radius: 8px; font-size: 12px; }
QLabel#FilePreview { background: #EAF4FC; border: 1px solid #C9E1F1;
    border-radius: 8px; padding: 12px; font-weight: 700; }
QLabel#FieldLabel { font-size: 12px; font-weight: 600; }
QPushButton { border-radius: 8px; padding: 9px 13px; font-weight: 600;
    min-height: 18px; }
QPushButton#YellowButton { background: #FFDA55; color: #15334E; border: 1px solid #F0C735; }
QPushButton#YellowButton:hover { background: #FFE477; }
QPushButton#PrimaryButton { background: #15334E; color: white; border: 1px solid #15334E; }
QPushButton#PrimaryButton:hover { background: #2C5878; }
QPushButton#OutlineButton { background: white; color: #15334E; border: 1px solid #C7D8E4; }
QPushButton#OutlineButton:hover { background: #EAF4FC; }
QPushButton#DangerOutlineButton { background: white; color: #A32B38; border: 1px solid #E8BFC4; }
QPushButton#DangerOutlineButton:hover { background: #FFF0F2; }
QPushButton:disabled { color: #98A5AF; background: #E9EFF3; border-color: #E0E6EA; }
QListWidget { background: white; border: 1px solid #E0E9F0; border-radius: 8px;
    padding: 5px; outline: none; }
QListWidget::item { border-radius: 7px; padding: 4px; }
QListWidget::item:selected { background: #C8EAFB; color: #15334E; }
QListWidget::item:hover { background: #EAF5FC; }
QListWidget#PageGrid::item:selected { background: #FFF1AD; }
QComboBox, QLineEdit { background: white; border: 1px solid #C7D8E4;
    border-radius: 8px; padding: 9px; min-height: 18px; }
QComboBox:focus, QLineEdit:focus { border: 2px solid #58BFEF; }
QStatusBar { background: #EAF4FC; color: #355A76; }
QSplitter::handle { background: transparent; }
"""


def main() -> None:
    app = QApplication(sys.argv)
    app.setApplicationName("DBB PDF Workspace")
    app.setStyle("Fusion")
    window = Workspace()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
