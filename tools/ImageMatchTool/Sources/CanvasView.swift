import AppKit

/// Which rectangle a mouse drag edits.
enum SelectionKind: Int {
    case searchArea = 0
    case template = 1
}

/// Integer pixel rect in image coordinates (inclusive origin, exclusive size).
struct PixelRect: Equatable {
    var x: Int
    var y: Int
    var w: Int
    var h: Int

    var x2: Int { x + w - 1 }
    var y2: Int { y + h - 1 }
}

/// Displays the screenshot at an integer-friendly zoom and lets the user drag rectangles.
final class CanvasView: NSView {
    var image: CGImage? { didSet { updateSize(); needsDisplay = true } }
    var zoom: CGFloat = 1 { didSet { updateSize(); needsDisplay = true } }

    var searchRect: PixelRect? { didSet { needsDisplay = true } }
    var templateRect: PixelRect? { didSet { needsDisplay = true } }
    /// Last match result drawn as a dashed rectangle.
    var matchRect: PixelRect? { didSet { needsDisplay = true } }

    /// Called whenever a rectangle changes through the mouse.
    var onSelectionChanged: (() -> Void)?
    /// Called with the hovered pixel position (nil when outside the image).
    var onHover: ((Int, Int)?) -> Void = { _ in }

    private var dragStart: (Int, Int)?
    private var dragKind: SelectionKind = .searchArea

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }

    private func updateSize() {
        guard let img = image else { setFrameSize(.zero); return }
        setFrameSize(NSSize(width: CGFloat(img.width) * zoom, height: CGFloat(img.height) * zoom))
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseMoved, .mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self))
    }

    override func draw(_ dirtyRect: NSRect) {
        NSColor.windowBackgroundColor.setFill()
        dirtyRect.fill()
        guard let img = image, let ctx = NSGraphicsContext.current?.cgContext else { return }

        // Nearest-neighbour scaling keeps individual pixels visible when zoomed in.
        ctx.saveGState()
        ctx.interpolationQuality = .none
        ctx.translateBy(x: 0, y: bounds.height)
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: bounds.width, height: bounds.height))
        ctx.restoreGState()

        if let r = searchRect { stroke(r, color: .systemRed, dashed: false) }
        if let r = templateRect { stroke(r, color: .systemGreen, dashed: false) }
        if let r = matchRect { stroke(r, color: .systemYellow, dashed: true) }
    }

    private func stroke(_ r: PixelRect, color: NSColor, dashed: Bool) {
        let rect = NSRect(x: CGFloat(r.x) * zoom, y: CGFloat(r.y) * zoom, width: CGFloat(r.w) * zoom, height: CGFloat(r.h) * zoom)
        let path = NSBezierPath(rect: rect.insetBy(dx: 0.5, dy: 0.5))
        path.lineWidth = 2
        if dashed { path.setLineDash([6, 4], count: 2, phase: 0) }
        color.setStroke()
        path.stroke()
    }

    /// Converts a view point to a pixel position clamped to the image.
    private func pixel(at event: NSEvent, clamp: Bool) -> (Int, Int)? {
        guard let img = image else { return nil }
        let p = convert(event.locationInWindow, from: nil)
        var x = Int(floor(p.x / zoom)), y = Int(floor(p.y / zoom))
        if clamp {
            x = min(max(x, 0), img.width - 1)
            y = min(max(y, 0), img.height - 1)
        } else if x < 0 || y < 0 || x >= img.width || y >= img.height {
            return nil
        }
        return (x, y)
    }

    override func mouseMoved(with event: NSEvent) {
        if let p = pixel(at: event, clamp: false) { onHover(p) } else { onHover(nil) }
    }

    override func mouseExited(with event: NSEvent) { onHover(nil) }

    // Left drag selects the search area; right drag (or Option + left drag on a trackpad)
    // selects the template image.
    override func mouseDown(with event: NSEvent) {
        beginDrag(event, kind: event.modifierFlags.contains(.option) ? .template : .searchArea)
    }

    override func rightMouseDown(with event: NSEvent) {
        beginDrag(event, kind: .template)
    }

    override func mouseDragged(with event: NSEvent) { continueDrag(event) }
    override func rightMouseDragged(with event: NSEvent) { continueDrag(event) }
    override func mouseUp(with event: NSEvent) { dragStart = nil }
    override func rightMouseUp(with event: NSEvent) { dragStart = nil }

    /// Suppress the default context menu so right drags are not interrupted.
    override func menu(for event: NSEvent) -> NSMenu? { nil }

    private func beginDrag(_ event: NSEvent, kind: SelectionKind) {
        dragStart = pixel(at: event, clamp: true)
        dragKind = kind
    }

    private func continueDrag(_ event: NSEvent) {
        guard let s = dragStart, let p = pixel(at: event, clamp: true) else { return }
        onHover(p)
        let rect = PixelRect(x: min(s.0, p.0), y: min(s.1, p.1), w: abs(p.0 - s.0) + 1, h: abs(p.1 - s.1) + 1)
        switch dragKind {
        case .searchArea: searchRect = rect
        case .template: templateRect = rect
        }
        onSelectionChanged?()
    }

    /// Mouse wheel zooms around the cursor. Trackpad two-finger scrolling still pans
    /// (use pinch to zoom there); hold Shift with the wheel to pan instead of zooming.
    override func scrollWheel(with event: NSEvent) {
        let isTrackpad = event.hasPreciseScrollingDeltas
        if isTrackpad || event.modifierFlags.contains(.shift) || event.scrollingDeltaY == 0 {
            super.scrollWheel(with: event)
            return
        }
        zoom(by: event.scrollingDeltaY > 0 ? 1.25 : 0.8, at: event)
    }

    /// Trackpad pinch zooms around the cursor.
    override func magnify(with event: NSEvent) {
        zoom(by: 1 + event.magnification, at: event)
    }

    /// Changes the zoom while keeping the image pixel under the cursor in place.
    private func zoom(by factor: CGFloat, at event: NSEvent) {
        guard image != nil, let clip = enclosingScrollView?.contentView else {
            zoom = min(max(zoom * factor, 0.1), 32)
            return
        }
        let cursorInView = convert(event.locationInWindow, from: nil)
        let cursorInClip = clip.convert(event.locationInWindow, from: nil)
        let imagePoint = NSPoint(x: cursorInView.x / zoom, y: cursorInView.y / zoom)
        let newZoom = min(max(zoom * factor, 0.1), 32)
        guard newZoom != zoom else { return }
        zoom = newZoom
        // Scroll so the same image pixel stays under the cursor.
        let origin = NSPoint(
            x: imagePoint.x * newZoom - (cursorInClip.x - clip.bounds.origin.x),
            y: imagePoint.y * newZoom - (cursorInClip.y - clip.bounds.origin.y)
        )
        clip.scroll(to: clip.constrainBoundsRect(NSRect(origin: origin, size: clip.bounds.size)).origin)
        enclosingScrollView?.reflectScrolledClipView(clip)
        onHover((Int(imagePoint.x), Int(imagePoint.y)))
    }
}

/// Window content view that accepts image files dragged in from Finder.
final class DropView: NSView {
    var onDropImage: ((URL) -> Void)?
    private static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "bmp", "tif", "tiff", "heic"]

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    private func imageURL(_ info: NSDraggingInfo) -> URL? {
        let urls = info.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]
        return urls?.first { Self.imageExtensions.contains($0.pathExtension.lowercased()) }
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        imageURL(sender) == nil ? [] : .copy
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let url = imageURL(sender) else { return false }
        onDropImage?(url)
        return true
    }
}
