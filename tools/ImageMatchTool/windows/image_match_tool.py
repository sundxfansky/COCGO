"""Windows port of tools/ImageMatchTool (the AppKit 图片区块匹配工具).

Open or drop a device screenshot, left-drag the search area (red), right-drag the template (green),
test the match score and save the template PNG plus the generated BlockTemplateSchema Kotlin code.
Behaviour and generated output follow Sources/MainWindowController.swift.
"""

from __future__ import annotations

import ctypes
import json
import math
import os
import sys
import threading
import tkinter as tk
from dataclasses import dataclass
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import List, Optional, Tuple

import numpy as np
from PIL import Image, ImageTk

from matcher import BlockTemplate, MatchResult, search

try:
    # Optional: lets image files be dragged from Explorer into the window.
    from tkinterdnd2 import DND_FILES, TkinterDnD
except ImportError:  # pragma: no cover - drag and drop is a convenience only
    DND_FILES = None
    TkinterDnD = None

# Template directory inside the repository; the plugin build packs every PNG in it.
REPO_TEMPLATE_PATH = "app/src/main/resources/com/coc/suncode/jar/templates"
SETTINGS_FILE = Path(os.environ.get("APPDATA", Path.home())) / "ImageMatchTool" / "settings.json"
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff"}
MIN_ZOOM, MAX_ZOOM = 0.1, 32.0
PANEL_WIDTH = 400


@dataclass
class PixelRect:
    """Integer pixel rect in image coordinates (inclusive origin, exclusive size)."""

    x: int
    y: int
    w: int
    h: int

    @property
    def x2(self) -> int:
        return self.x + self.w - 1

    @property
    def y2(self) -> int:
        return self.y + self.h - 1


class ImageCanvas(ttk.Frame):
    """Displays the screenshot with nearest-neighbour zoom and lets the user drag rectangles.

    Only the visible part of the image is scaled and drawn, so high zoom levels stay cheap.
    """

    SEARCH, TEMPLATE = 0, 1

    def __init__(self, master: tk.Misc, on_selection_changed, on_hover):
        super().__init__(master)
        self.on_selection_changed = on_selection_changed
        self.on_hover = on_hover
        self.image: Optional[Image.Image] = None
        self.zoom = 1.0
        self.search_rect: Optional[PixelRect] = None
        self.template_rect: Optional[PixelRect] = None
        # Last match result drawn as a dashed rectangle.
        self.match_rect: Optional[PixelRect] = None

        self._drag_start: Optional[Tuple[int, int]] = None
        self._drag_kind = self.SEARCH
        self._photo: Optional[ImageTk.PhotoImage] = None
        self._render_pending = False

        self.canvas = tk.Canvas(self, background="#3C3C3C", highlightthickness=0, cursor="crosshair")
        self.hbar = ttk.Scrollbar(self, orient=tk.HORIZONTAL, command=self._xview)
        self.vbar = ttk.Scrollbar(self, orient=tk.VERTICAL, command=self._yview)
        self.canvas.configure(xscrollcommand=self._on_xscroll, yscrollcommand=self._on_yscroll)
        self.canvas.grid(row=0, column=0, sticky="nsew")
        self.vbar.grid(row=0, column=1, sticky="ns")
        self.hbar.grid(row=1, column=0, sticky="ew")
        self.rowconfigure(0, weight=1)
        self.columnconfigure(0, weight=1)

        c = self.canvas
        c.bind("<Configure>", lambda _e: self.schedule_render())
        c.bind("<Motion>", self._on_motion)
        c.bind("<Leave>", lambda _e: self.on_hover(None))
        # Left drag selects the search area; right drag (or Alt + left drag) selects the template image.
        c.bind("<ButtonPress-1>", lambda e: self._begin_drag(e, self.SEARCH))
        c.bind("<Alt-ButtonPress-1>", lambda e: self._begin_drag(e, self.TEMPLATE))
        c.bind("<ButtonPress-3>", lambda e: self._begin_drag(e, self.TEMPLATE))
        c.bind("<B1-Motion>", self._continue_drag)
        c.bind("<B3-Motion>", self._continue_drag)
        c.bind("<ButtonRelease-1>", self._end_drag)
        c.bind("<ButtonRelease-3>", self._end_drag)
        # Middle drag pans the view.
        c.bind("<ButtonPress-2>", lambda e: c.scan_mark(e.x, e.y))
        c.bind("<B2-Motion>", self._on_pan)
        # Wheel zooms around the cursor; Shift+wheel pans vertically, Ctrl+wheel pans horizontally.
        c.bind("<MouseWheel>", self._on_wheel)
        c.bind("<Shift-MouseWheel>", lambda e: self._wheel_pan(e, vertical=True))
        c.bind("<Control-MouseWheel>", lambda e: self._wheel_pan(e, vertical=False))

    # ----- Image and zoom -----

    def set_image(self, image: Optional[Image.Image]) -> None:
        self.image = image
        self._update_scroll_region()
        self.canvas.xview_moveto(0)
        self.canvas.yview_moveto(0)
        self.schedule_render()

    def viewport_size(self) -> Tuple[int, int]:
        self.canvas.update_idletasks()
        return max(1, self.canvas.winfo_width()), max(1, self.canvas.winfo_height())

    def set_zoom(self, zoom: float, anchor: Optional[Tuple[int, int]] = None) -> None:
        """Changes the zoom while keeping the image pixel under [anchor] (viewport coords) in place."""
        new_zoom = min(max(zoom, MIN_ZOOM), MAX_ZOOM)
        if self.image is None:
            self.zoom = new_zoom
            return
        if anchor is None:
            vw, vh = self.viewport_size()
            anchor = (vw // 2, vh // 2)
        ax, ay = anchor
        image_x = self.canvas.canvasx(ax) / self.zoom
        image_y = self.canvas.canvasy(ay) / self.zoom
        if new_zoom == self.zoom:
            return
        self.zoom = new_zoom
        self._update_scroll_region()
        # Scroll so the same image pixel stays under the anchor.
        total_w, total_h = self._content_size()
        self.canvas.xview_moveto(max(0.0, image_x * new_zoom - ax) / total_w)
        self.canvas.yview_moveto(max(0.0, image_y * new_zoom - ay) / total_h)
        self.schedule_render()
        self.on_hover((int(image_x), int(image_y)) if self._inside(int(image_x), int(image_y)) else None)

    def _content_size(self) -> Tuple[float, float]:
        if self.image is None:
            return 1.0, 1.0
        return max(1.0, self.image.width * self.zoom), max(1.0, self.image.height * self.zoom)

    def _update_scroll_region(self) -> None:
        w, h = self._content_size() if self.image is not None else (0, 0)
        self.canvas.configure(scrollregion=(0, 0, w, h))

    # ----- Rendering -----

    def _xview(self, *args) -> None:
        self.canvas.xview(*args)
        self.schedule_render()

    def _yview(self, *args) -> None:
        self.canvas.yview(*args)
        self.schedule_render()

    def _on_xscroll(self, first, last) -> None:
        self.hbar.set(first, last)
        self.schedule_render()

    def _on_yscroll(self, first, last) -> None:
        self.vbar.set(first, last)
        self.schedule_render()

    def schedule_render(self) -> None:
        # Coalesce scroll/zoom/resize events into one redraw per idle cycle.
        if not self._render_pending:
            self._render_pending = True
            self.after_idle(self._render)

    def _render(self) -> None:
        self._render_pending = False
        c = self.canvas
        c.delete("all")
        if self.image is None:
            return
        z = self.zoom
        vx, vy = c.canvasx(0), c.canvasy(0)
        vw, vh = max(1, c.winfo_width()), max(1, c.winfo_height())
        # Image pixels covering the viewport, with one pixel of margin.
        ix0 = max(0, int(math.floor(vx / z)))
        iy0 = max(0, int(math.floor(vy / z)))
        ix1 = min(self.image.width, int(math.ceil((vx + vw) / z)) + 1)
        iy1 = min(self.image.height, int(math.ceil((vy + vh) / z)) + 1)
        if ix1 > ix0 and iy1 > iy0:
            left, top = round(ix0 * z), round(iy0 * z)
            out_w = max(1, round(ix1 * z) - left)
            out_h = max(1, round(iy1 * z) - top)
            # Nearest-neighbour scaling keeps individual pixels visible when zoomed in.
            part = self.image.crop((ix0, iy0, ix1, iy1)).resize((out_w, out_h), Image.Resampling.NEAREST)
            self._photo = ImageTk.PhotoImage(part)
            c.create_image(left, top, image=self._photo, anchor=tk.NW)
        if self.search_rect:
            self._stroke(self.search_rect, "#FF3B30", dashed=False)
        if self.template_rect:
            self._stroke(self.template_rect, "#34C759", dashed=False)
        if self.match_rect:
            self._stroke(self.match_rect, "#FFCC00", dashed=True)

    def _stroke(self, r: PixelRect, color: str, dashed: bool) -> None:
        z = self.zoom
        self.canvas.create_rectangle(
            r.x * z + 1, r.y * z + 1, (r.x + r.w) * z - 1, (r.y + r.h) * z - 1,
            outline=color, width=2, dash=(6, 4) if dashed else None,
        )

    # ----- Mouse -----

    def _inside(self, x: int, y: int) -> bool:
        return self.image is not None and 0 <= x < self.image.width and 0 <= y < self.image.height

    def _pixel(self, event, clamp: bool) -> Optional[Tuple[int, int]]:
        """Converts a viewport point to a pixel position, optionally clamped to the image."""
        if self.image is None:
            return None
        x = int(math.floor(self.canvas.canvasx(event.x) / self.zoom))
        y = int(math.floor(self.canvas.canvasy(event.y) / self.zoom))
        if clamp:
            return min(max(x, 0), self.image.width - 1), min(max(y, 0), self.image.height - 1)
        return (x, y) if self._inside(x, y) else None

    def _on_motion(self, event) -> None:
        self.on_hover(self._pixel(event, clamp=False))

    def _begin_drag(self, event, kind: int) -> str:
        self.canvas.focus_set()
        self._drag_start = self._pixel(event, clamp=True)
        self._drag_kind = kind
        # Stop the plain <ButtonPress-1> binding from also firing for Alt+click.
        return "break"

    def _continue_drag(self, event) -> None:
        p = self._pixel(event, clamp=True)
        if self._drag_start is None or p is None:
            return
        self.on_hover(p)
        s = self._drag_start
        rect = PixelRect(min(s[0], p[0]), min(s[1], p[1]), abs(p[0] - s[0]) + 1, abs(p[1] - s[1]) + 1)
        if self._drag_kind == self.SEARCH:
            self.search_rect = rect
        else:
            self.template_rect = rect
        self.on_selection_changed()

    def _end_drag(self, _event) -> None:
        self._drag_start = None

    def _on_pan(self, event) -> None:
        self.canvas.scan_dragto(event.x, event.y, gain=1)
        self.schedule_render()

    def _on_wheel(self, event) -> None:
        if event.delta == 0:
            return
        self.set_zoom(self.zoom * (1.25 if event.delta > 0 else 0.8), anchor=(event.x, event.y))

    def _wheel_pan(self, event, vertical: bool) -> str:
        steps = -1 if event.delta > 0 else 1
        if vertical:
            self.canvas.yview_scroll(steps * 3, "units")
        else:
            self.canvas.xview_scroll(steps * 3, "units")
        self.schedule_render()
        return "break"


def find_repo_root(start: Path) -> Optional[Path]:
    """Walks up from [start] until a directory containing settings.gradle.kts is found."""
    for d in [start, *start.parents]:
        if (d / "settings.gradle.kts").exists():
            return d
    return None


def default_template_directory() -> Path:
    """Finds the repository's template directory from the script location and the working directory."""
    for start in (Path(__file__).resolve().parent, Path.cwd()):
        root = find_repo_root(start)
        if root is not None:
            return root / REPO_TEMPLATE_PATH
    return Path.home() / "Desktop" / "templates"


def load_settings() -> dict:
    try:
        return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_settings(settings: dict) -> None:
    try:
        SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS_FILE.write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        pass  # Remembering the directory is only a convenience.


def sanitize_property_name(text: str) -> str:
    """Keeps letters, digits and '_' and makes sure the name starts with a letter or '_'."""
    cleaned = "".join(ch for ch in text if ch.isalpha() or ch.isdigit() or ch == "_")
    if cleaned and (cleaned[0].isalpha() or cleaned[0] == "_"):
        return cleaned
    return "Template" + cleaned


def sanitize_image_name(text: str) -> str:
    """File name without extension, restricted to what BlockTemplateSchema accepts ([A-Za-z0-9_])."""
    cleaned = "".join(ch for ch in text if (ch.isascii() and ch.isalnum()) or ch == "_")
    return cleaned or "template"


def generate_code(prop: str, image_name: str, search: PixelRect, tpl_rect: PixelRect,
                  block: int, similarity: str, name: str) -> str:
    """Kotlin snippet identical to MainWindowController.regenerate() in the Swift tool."""
    name = name.replace('"', '\\"')
    return (
        "// Interface declaration\n"
        f"val {prop}: BlockTemplateSchema\n"
        "\n"
        f"// Implementation: reads {image_name}.png ({tpl_rect.w}x{tpl_rect.h}, captured at {tpl_rect.x}, {tpl_rect.y}) from the templates directory\n"
        f"override val {prop} = BlockTemplateSchema.fromImage(\n"
        f"    {search.x}, {search.y}, {search.x2}, {search.y2}, \"{image_name}\", {block}, {similarity}, \"{name}\"\n"
        ")\n"
        "\n"
        "// Usage\n"
        "// import com.coc.suncode.jar.code.colorschema.BlockTemplateSchema\n"
        "// import com.coc.suncode.jar.code.universal.colors.findBlockTemplate\n"
        f"// findBlockTemplate(MyColors.{prop})?.let {{ match -> val (cx, cy) = match.center(MyColors.{prop}) }}"
    )


def swift_double(value: float) -> str:
    """Formats like Swift's String(Double): shortest round-trip, always with a decimal part."""
    text = repr(float(value))
    return text if ("." in text or "e" in text) else text + ".0"


def to_rgb_pixels(image: Image.Image) -> Image.Image:
    """Converts to 8-bit RGB without colour management, like drawing into an RGB CGContext.

    Pillow's RGBA -> RGB conversion discards alpha instead of compositing, matching noneSkipLast.
    """
    return image if image.mode == "RGB" else image.convert("RGB")


class MainWindow:
    """Main window: screenshot canvas on the left, parameters and generated code on the right."""

    HINT = ("左键拖动：框选搜索区域（红框）\n"
            "右键拖动 / Alt+左键：框选目标图片（绿框）\n"
            "滚轮缩放（Shift+滚轮上下平移，Ctrl+滚轮左右平移，中键拖动平移）\n"
            "可以把截图直接拖进窗口，Ctrl+O 打开，Ctrl+V 粘贴剪贴板截图")

    def __init__(self, root: tk.Tk):
        self.root = root
        self.settings = load_settings()
        self.template_directory = Path(self.settings.get("templateDirectory") or default_template_directory())
        self.image: Optional[Image.Image] = None
        self.pixels: Optional[np.ndarray] = None
        self._match_job = 0

        root.title("图片区块匹配工具")
        root.geometry("1400x860")
        root.minsize(900, 600)
        self._build_ui()
        root.bind_all("<Control-o>", lambda _e: self.open_image())
        root.bind_all("<Control-v>", self._paste_image)
        if DND_FILES is not None and hasattr(root, "drop_target_register"):
            root.drop_target_register(DND_FILES)
            root.dnd_bind("<<Drop>>", self._on_drop)
        self.regenerate()

    # ----- Layout -----

    def _build_ui(self) -> None:
        root = self.root
        self.canvas = ImageCanvas(root, self.selection_changed_from_canvas, self.update_hover)
        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        panel = ttk.Frame(root, padding=12, width=PANEL_WIDTH)
        panel.pack(side=tk.RIGHT, fill=tk.Y)
        panel.pack_propagate(False)
        wrap = PANEL_WIDTH - 30

        def row() -> ttk.Frame:
            f = ttk.Frame(panel)
            f.pack(fill=tk.X, pady=(0, 8))
            return f

        r = row()
        for text, cmd in (("打开截图", self.open_image), ("缩小", self.zoom_out), ("1:1", self.zoom_reset), ("放大", self.zoom_in)):
            ttk.Button(r, text=text, command=cmd).pack(side=tk.LEFT, padx=(0, 6))

        self.hover_var = tk.StringVar(value=" ")
        ttk.Label(panel, textvariable=self.hover_var, font=("Consolas", 9)).pack(fill=tk.X, pady=(0, 8))
        ttk.Label(panel, text=self.HINT, foreground="#666666", wraplength=wrap, justify=tk.LEFT).pack(fill=tk.X, pady=(0, 8))

        self.search_vars = self._rect_section(panel, "搜索区域 x, y, 宽, 高（红框）", self._on_search_fields)
        self.template_vars = self._rect_section(panel, "目标图片 x, y, 宽, 高（绿框）", self._on_template_fields)

        r = row()
        ttk.Label(r, text="区块大小").pack(side=tk.LEFT)
        self.block_var = tk.StringVar(value="5")
        spin = ttk.Spinbox(r, from_=1, to=16, width=4, textvariable=self.block_var, command=self._on_block_changed)
        spin.pack(side=tk.LEFT, padx=(6, 12))
        self._bind_commit(spin, self._on_block_changed)
        ttk.Label(r, text="相似度").pack(side=tk.LEFT)
        self.similarity_var = tk.StringVar(value="0.95")
        sim = ttk.Entry(r, width=8, textvariable=self.similarity_var)
        sim.pack(side=tk.LEFT, padx=(6, 0))
        self._bind_commit(sim, self.regenerate)

        self.property_var = self._labeled_entry(row(), "属性名", "NewTemplate")
        self.image_name_var = self._labeled_entry(row(), "图片名", "new_template")
        self.name_var = self._labeled_entry(row(), "显示名", "新图片")

        r = row()
        ttk.Button(r, text="模板目录…", command=self.choose_directory).pack(side=tk.LEFT)
        self.directory_var = tk.StringVar()
        ttk.Label(r, textvariable=self.directory_var, foreground="#666666").pack(side=tk.LEFT, padx=(6, 0), fill=tk.X)
        self._update_directory_label()

        self.info_var = tk.StringVar(value="打开或拖入一张设备截图开始。")
        ttk.Label(panel, textvariable=self.info_var, wraplength=wrap, justify=tk.LEFT).pack(fill=tk.X, pady=(0, 8))

        r = row()
        ttk.Button(r, text="测试匹配", command=self.test_match).pack(side=tk.LEFT, padx=(0, 6))
        ttk.Button(r, text="保存图片并复制代码", command=self.save_template_image).pack(side=tk.LEFT)

        self.result_var = tk.StringVar(value=" ")
        ttk.Label(panel, textvariable=self.result_var, wraplength=wrap, justify=tk.LEFT).pack(fill=tk.X, pady=(0, 8))

        code_frame = ttk.Frame(panel)
        code_frame.pack(fill=tk.BOTH, expand=True)
        self.code_text = tk.Text(code_frame, wrap=tk.WORD, font=("Consolas", 9), height=10, relief=tk.SUNKEN, borderwidth=1)
        code_scroll = ttk.Scrollbar(code_frame, orient=tk.VERTICAL, command=self.code_text.yview)
        self.code_text.configure(yscrollcommand=code_scroll.set, state=tk.DISABLED)
        code_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.code_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    @staticmethod
    def _bind_commit(widget: tk.Widget, callback) -> None:
        # Mirrors controlTextDidEndEditing: apply on Enter and when the field loses focus.
        widget.bind("<Return>", lambda _e: callback())
        widget.bind("<FocusOut>", lambda _e: callback())

    def _labeled_entry(self, parent: ttk.Frame, label: str, value: str) -> tk.StringVar:
        ttk.Label(parent, text=label, width=6).pack(side=tk.LEFT)
        var = tk.StringVar(value=value)
        entry = ttk.Entry(parent, textvariable=var)
        entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(6, 0))
        self._bind_commit(entry, self.regenerate)
        return var

    def _rect_section(self, panel: ttk.Frame, title: str, on_commit) -> List[tk.StringVar]:
        ttk.Label(panel, text=title).pack(fill=tk.X, pady=(0, 4))
        r = ttk.Frame(panel)
        r.pack(fill=tk.X, pady=(0, 8))
        variables = []
        for _ in range(4):
            var = tk.StringVar()
            entry = ttk.Entry(r, width=8, textvariable=var)
            entry.pack(side=tk.LEFT, padx=(0, 6))
            self._bind_commit(entry, on_commit)
            variables.append(var)
        return variables

    def _update_directory_label(self) -> None:
        path = str(self.template_directory)
        # Truncate the head like NSLineBreakMode.byTruncatingHead.
        self.directory_var.set(path if len(path) <= 42 else "…" + path[-41:])

    # ----- Image loading -----

    def open_image(self) -> None:
        path = filedialog.askopenfilename(
            title="打开截图",
            filetypes=[("图片", "*.png *.jpg *.jpeg *.bmp *.tif *.tiff"), ("所有文件", "*.*")],
        )
        if path:
            self.load(Path(path))

    def _on_drop(self, event) -> None:
        for item in self.root.tk.splitlist(event.data):
            p = Path(item)
            if p.suffix.lower() in IMAGE_EXTENSIONS:
                self.load(p)
                return

    def _paste_image(self, event) -> Optional[str]:
        # Keep normal paste inside text fields.
        if isinstance(event.widget, (tk.Entry, ttk.Entry, ttk.Spinbox, tk.Text)):
            return None
        try:
            from PIL import ImageGrab
            data = ImageGrab.grabclipboard()
        except Exception:  # noqa: BLE001 - clipboard access is best effort
            data = None
        if isinstance(data, Image.Image):
            self.set_image(data, "剪贴板")
        elif isinstance(data, list):
            for item in data:
                if Path(item).suffix.lower() in IMAGE_EXTENSIONS:
                    self.load(Path(item))
                    break
        return "break"

    def load(self, path: Path) -> None:
        try:
            with Image.open(path) as img:
                img.load()
                image = to_rgb_pixels(img)
        except Exception as e:  # noqa: BLE001 - any decode failure is reported the same way
            self.alert(f"无法读取图片：{path.name}\n{e}")
            return
        self.set_image(image, path.name)

    def set_image(self, image: Image.Image, title: str) -> None:
        image = to_rgb_pixels(image)
        self.image = image
        self.pixels = np.asarray(image, dtype=np.uint8)
        c = self.canvas
        c.search_rect = PixelRect(0, 0, image.width, image.height)
        c.template_rect = None
        c.match_rect = None
        # Fit large screenshots into the visible area.
        vw, vh = c.viewport_size()
        c.zoom = min(1.0, vw / image.width, vh / image.height)
        c.set_image(image)
        self.root.title(f"图片区块匹配工具 - {title} ({image.width}x{image.height})")
        self.result_var.set(" ")
        self.sync_fields_from_canvas()
        self.regenerate()

    # ----- Zoom -----

    def zoom_in(self) -> None:
        self.canvas.set_zoom(self.canvas.zoom * 1.5)

    def zoom_out(self) -> None:
        self.canvas.set_zoom(self.canvas.zoom / 1.5)

    def zoom_reset(self) -> None:
        self.canvas.set_zoom(1.0)

    # ----- Selection sync -----

    def choose_directory(self) -> None:
        path = filedialog.askdirectory(
            title=f"选择模板图片目录（项目内为 {REPO_TEMPLATE_PATH}）",
            initialdir=str(self.template_directory if self.template_directory.exists() else Path.home()),
            mustexist=False,
        )
        if not path:
            return
        self.template_directory = Path(path)
        self.settings["templateDirectory"] = str(self.template_directory)
        save_settings(self.settings)
        self._update_directory_label()
        self.regenerate()

    def selection_changed_from_canvas(self) -> None:
        self.canvas.match_rect = None
        self.sync_fields_from_canvas()
        self.regenerate()

    def sync_fields_from_canvas(self) -> None:
        self._fill(self.search_vars, self.canvas.search_rect)
        self._fill(self.template_vars, self.canvas.template_rect)

    @staticmethod
    def _fill(variables: List[tk.StringVar], r: Optional[PixelRect]) -> None:
        values = [r.x, r.y, r.w, r.h] if r else ["", "", "", ""]
        for var, v in zip(variables, values):
            var.set(str(v))

    def _rect_from(self, variables: List[tk.StringVar]) -> Optional[PixelRect]:
        """Reads a rect from the four fields, clamped to the image."""
        if self.image is None:
            return None
        try:
            x, y, w, h = (int(v.get().strip()) for v in variables)
        except ValueError:
            return None
        if w <= 0 or h <= 0:
            return None
        cx = min(max(x, 0), self.image.width - 1)
        cy = min(max(y, 0), self.image.height - 1)
        return PixelRect(cx, cy, min(w, self.image.width - cx), min(h, self.image.height - cy))

    def _on_search_fields(self) -> None:
        self.canvas.search_rect = self._rect_from(self.search_vars)
        self._fields_committed()

    def _on_template_fields(self) -> None:
        self.canvas.template_rect = self._rect_from(self.template_vars)
        self._fields_committed()

    def _on_block_changed(self) -> None:
        self.block_var.set(str(self.block_size))
        self._fields_committed()

    def _fields_committed(self) -> None:
        self.canvas.match_rect = None
        self.canvas.schedule_render()
        self.regenerate()

    def update_hover(self, p: Optional[Tuple[int, int]]) -> None:
        if p is None or self.pixels is None:
            self.hover_var.set(" ")
            return
        r, g, b = (int(v) for v in self.pixels[p[1], p[0]])
        self.hover_var.set(f"坐标 {p[0]}, {p[1]}   颜色 #{r:02X}{g:02X}{b:02X}   缩放 {self.canvas.zoom * 100:.0f}%")

    # ----- Code generation -----

    @property
    def block_size(self) -> int:
        try:
            value = int(self.block_var.get().strip())
        except ValueError:
            value = 5
        return min(max(value, 1), 16)

    @property
    def similarity(self) -> Optional[float]:
        try:
            s = float(self.similarity_var.get().strip())
        except ValueError:
            return None
        return s if 0 < s <= 1 else None

    @property
    def property_name(self) -> str:
        return sanitize_property_name(self.property_var.get())

    @property
    def image_name(self) -> str:
        return sanitize_image_name(self.image_name_var.get())

    @property
    def template_file(self) -> Path:
        return self.template_directory / f"{self.image_name}.png"

    def current_template(self) -> Optional[BlockTemplate]:
        t = self.canvas.template_rect
        if self.pixels is None or t is None:
            return None
        return BlockTemplate.make(self.pixels, t.x, t.y, t.w, t.h, self.block_size)

    def _set_code(self, text: str) -> None:
        self.code_text.configure(state=tk.NORMAL)
        self.code_text.delete("1.0", tk.END)
        self.code_text.insert("1.0", text)
        self.code_text.configure(state=tk.DISABLED)

    def code(self) -> str:
        return self.code_text.get("1.0", "end-1c")

    def regenerate(self) -> None:
        """Rebuilds the info text and the generated Kotlin code."""
        if self.image is None:
            self._set_code("")
            return
        s = self.canvas.search_rect
        if s is None:
            self.info_var.set("请用左键框选搜索区域（红框）。")
            self._set_code("")
            return
        t = self.canvas.template_rect
        tpl = self.current_template()
        if t is None or tpl is None:
            self.info_var.set(f"请用右键框选目标图片（绿框），宽高至少为一个区块（{self.block_size}px）。")
            self._set_code("")
            return
        info = f"目标图片 {t.w}x{t.h}，区块 {tpl.block}px → {tpl.cols}x{tpl.rows} = {tpl.count} 个区块"
        if tpl.pixel_width != t.w or tpl.pixel_height != t.h:
            info += f"\n实际参与匹配 {tpl.pixel_width}x{tpl.pixel_height}，右侧/底部多余像素被忽略。"
        if s.w < tpl.pixel_width or s.h < tpl.pixel_height:
            info += "\n⚠️ 搜索区域比目标图片小，无法匹配。"
        if self.similarity is None:
            info += "\n⚠️ 相似度需在 0~1 之间。"
        if self.template_file.exists():
            info += f"\n目录中已有 {self.image_name}.png，保存时会覆盖。"
        self.info_var.set(info)
        sim = swift_double(self.similarity) if self.similarity is not None else "0.95"
        self._set_code(generate_code(self.property_name, self.image_name, s, t, tpl.block, sim, self.name_var.get()))

    # ----- Actions -----

    def test_match(self) -> None:
        s = self.canvas.search_rect
        tpl = self.current_template()
        if self.pixels is None or s is None or tpl is None:
            self.alert("请先框选搜索区域和目标图片。")
            return
        self.result_var.set("匹配中…")
        threshold = self.similarity if self.similarity is not None else 0.95
        pixels = self.pixels
        self._match_job += 1
        job = self._match_job

        # Tk is not thread safe: the worker only stores its result and the main thread polls for it.
        box: List[Optional[MatchResult]] = []
        thread = threading.Thread(target=lambda: box.append(search(pixels, tpl, s.x, s.y, s.x2, s.y2)), daemon=True)
        thread.start()

        def poll() -> None:
            if thread.is_alive():
                self.root.after(20, poll)
            elif job == self._match_job:
                # Results from a match superseded by a newer one are dropped.
                self.show_result(box[0] if box else None, tpl, threshold)

        self.root.after(20, poll)

    def show_result(self, r: Optional[MatchResult], tpl: BlockTemplate, threshold: float) -> None:
        if r is None:
            self.canvas.match_rect = None
            self.canvas.schedule_render()
            self.result_var.set("搜索区域比目标图片小，无法匹配。")
            return
        self.canvas.match_rect = PixelRect(r.x, r.y, tpl.pixel_width, tpl.pixel_height)
        self.canvas.schedule_render()
        text = f"最佳位置 {r.x}, {r.y}   相似度 {r.score:.4f}   {'✅ 命中' if r.score >= threshold else '❌ 低于阈值'}\n"
        if r.second_score is not None:
            # A large gap between the best and the runner-up means the threshold is easy to pick.
            text += f"次佳（不重叠）相似度 {r.second_score:.4f}，建议阈值取在两者之间"
            if r.second_score >= threshold:
                text += "\n⚠️ 次佳位置也超过阈值，可能误判，请调高阈值或换更有特征的目标。"
        else:
            text += "搜索区域内没有其他不重叠的位置。"
        text += f"\n耗时 {r.elapsed_ms:.1f} ms（Windows 上全像素精确搜索）"
        self.result_var.set(text)

    def copy_code(self) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(self.code())
        self.root.update()  # Keep the clipboard content after the window loses focus.

    def save_template_image(self) -> None:
        """Saves the template crop as <imageName>.png into the template directory and copies the code."""
        t = self.canvas.template_rect
        if self.image is None or t is None or not self.code():
            self.alert("请先框选搜索区域和目标图片。")
            return
        try:
            self.template_directory.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            self.alert(f"无法创建目录：{self.template_directory}\n{e}")
            return
        path = self.template_file
        # Raw RGB values without an embedded colour profile, so Android decodes the same values as sRGB.
        cropped = self.image.crop((t.x, t.y, t.x + t.w, t.y + t.h))
        try:
            cropped.save(path, format="PNG")
        except OSError as e:
            self.alert(f"保存失败：{path}\n{e}")
            return
        self.copy_code()
        self.regenerate()
        self.result_var.set(f"已保存 {path.name} 到模板目录，代码已复制到剪贴板。")

    @staticmethod
    def alert(message: str) -> None:
        messagebox.showwarning("图片区块匹配工具", message)


def main() -> None:
    # Render at native resolution on high-DPI displays so one image pixel maps to one screen pixel at 100%.
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    root = TkinterDnD.Tk() if TkinterDnD is not None else tk.Tk()
    window = MainWindow(root)
    # Allow `image_match_tool.py <screenshot.png>` from the command line.
    if len(sys.argv) > 1:
        root.after(100, lambda: window.load(Path(sys.argv[1])))
    root.mainloop()


if __name__ == "__main__":
    main()
