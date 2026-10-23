import AppKit
import ImageIO
import UniformTypeIdentifiers

/// Main window: screenshot canvas on the left, parameters and generated code on the right.
final class MainWindowController: NSWindowController, NSTextFieldDelegate {
    /// Template directory inside the repository; the plugin build packs every PNG in it.
    static let repoTemplatePath = "app/src/main/resources/com/coc/suncode/jar/templates"
    private static let directoryKey = "templateDirectory"

    private let canvas = CanvasView()
    private let scrollView = NSScrollView()

    private let hintLabel = NSTextField(wrappingLabelWithString: "左键拖动：框选搜索区域（红框）\n右键拖动 / Option+左键：框选目标图片（绿框）\n滚轮缩放（Shift+滚轮平移），触控板双指平移、捏合缩放\n可以把截图直接拖进窗口")
    private let searchFields = (0..<4).map { _ in NSTextField() }
    private let templateFields = (0..<4).map { _ in NSTextField() }
    private let blockField = NSTextField(string: "5")
    private let blockStepper = NSStepper()
    private let similarityField = NSTextField(string: "0.95")
    private let propertyField = NSTextField(string: "NewTemplate")
    private let imageNameField = NSTextField(string: "new_template")
    private let nameField = NSTextField(string: "新图片")
    private let directoryLabel = NSTextField(labelWithString: "")
    private let infoLabel = NSTextField(wrappingLabelWithString: "打开或拖入一张设备截图开始。")
    private let hoverLabel = NSTextField(labelWithString: " ")
    private let resultLabel = NSTextField(wrappingLabelWithString: " ")
    private let codeView = NSTextView()

    private var image: CGImage?
    private var pixels: PixelBuffer?

    /// Directory template PNGs are saved to; remembered between launches.
    private var templateDirectory: URL {
        didSet {
            UserDefaults.standard.set(templateDirectory.path, forKey: Self.directoryKey)
            updateDirectoryLabel()
            regenerate()
        }
    }

    init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1400, height: 860),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false
        )
        window.title = "图片区块匹配工具"
        window.center()
        templateDirectory = UserDefaults.standard.string(forKey: Self.directoryKey).map { URL(fileURLWithPath: $0) }
            ?? Self.defaultTemplateDirectory()
        super.init(window: window)
        buildUI()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// Finds the repository's template directory by walking up from the app bundle and the working directory.
    private static func defaultTemplateDirectory() -> URL {
        for start in [Bundle.main.bundleURL, URL(fileURLWithPath: FileManager.default.currentDirectoryPath)] {
            var dir = start
            while dir.path != "/" {
                if FileManager.default.fileExists(atPath: dir.appendingPathComponent("settings.gradle.kts").path) {
                    return dir.appendingPathComponent(repoTemplatePath)
                }
                dir = dir.deletingLastPathComponent()
            }
        }
        return FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Desktop/templates")
    }

    // MARK: - Layout

    private func buildUI() {
        guard let window else { return }
        // The content view accepts dropped image files.
        let content = DropView(frame: window.contentRect(forFrameRect: window.frame))
        content.onDropImage = { [weak self] url in self?.load(url: url) }
        window.contentView = content

        scrollView.documentView = canvas
        scrollView.hasVerticalScroller = true
        scrollView.hasHorizontalScroller = true
        scrollView.backgroundColor = .underPageBackgroundColor
        scrollView.translatesAutoresizingMaskIntoConstraints = false

        canvas.onSelectionChanged = { [weak self] in self?.selectionChangedFromCanvas() }
        canvas.onHover = { [weak self] p in self?.updateHover(p) }

        hintLabel.textColor = .secondaryLabelColor
        directoryLabel.textColor = .secondaryLabelColor
        directoryLabel.lineBreakMode = .byTruncatingHead
        directoryLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        updateDirectoryLabel()

        blockStepper.minValue = 1
        blockStepper.maxValue = 16
        blockStepper.integerValue = 5
        blockStepper.target = self
        blockStepper.action = #selector(blockStepperChanged)

        for f in searchFields + templateFields + [blockField, similarityField, propertyField, imageNameField, nameField] {
            f.delegate = self
        }

        codeView.isEditable = false
        codeView.isRichText = false
        codeView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        codeView.textContainer?.widthTracksTextView = true
        codeView.autoresizingMask = [.width]
        let codeScroll = NSScrollView()
        codeScroll.documentView = codeView
        codeScroll.hasVerticalScroller = true
        codeScroll.borderType = .bezelBorder
        codeScroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 160).isActive = true

        let stack = NSStackView(views: [
            row([button("打开截图", #selector(openImage)), button("缩小", #selector(zoomOut)), button("1:1", #selector(zoomReset)), button("放大", #selector(zoomIn))]),
            hoverLabel,
            hintLabel,
            section("搜索区域 x, y, 宽, 高（红框）", searchFields),
            section("目标图片 x, y, 宽, 高（绿框）", templateFields),
            row([label("区块大小"), sized(blockField, 44), blockStepper, label("相似度"), sized(similarityField, 60)]),
            row([label("属性名"), propertyField]),
            row([label("图片名"), imageNameField]),
            row([label("显示名"), nameField]),
            row([button("模板目录…", #selector(chooseDirectory)), directoryLabel]),
            infoLabel,
            row([button("测试匹配", #selector(testMatch)), button("保存图片并复制代码", #selector(saveTemplateImage))]),
            resultLabel,
            codeScroll,
        ])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        stack.translatesAutoresizingMaskIntoConstraints = false
        for v in [hintLabel, infoLabel, resultLabel, codeScroll] {
            v.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -24).isActive = true
        }

        content.addSubview(scrollView)
        content.addSubview(stack)
        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            scrollView.topAnchor.constraint(equalTo: content.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            scrollView.trailingAnchor.constraint(equalTo: stack.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor),
            stack.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            stack.widthAnchor.constraint(equalToConstant: 400),
        ])
        regenerate()
    }

    private func label(_ s: String) -> NSTextField { NSTextField(labelWithString: s) }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        NSButton(title: title, target: self, action: action)
    }

    private func sized(_ f: NSTextField, _ width: CGFloat) -> NSTextField {
        f.widthAnchor.constraint(equalToConstant: width).isActive = true
        return f
    }

    private func row(_ views: [NSView]) -> NSStackView {
        let r = NSStackView(views: views)
        r.orientation = .horizontal
        r.spacing = 6
        return r
    }

    private func section(_ title: String, _ fields: [NSTextField]) -> NSStackView {
        fields.forEach { _ = sized($0, 64) }
        let s = NSStackView(views: [label(title), row(fields)])
        s.orientation = .vertical
        s.alignment = .leading
        s.spacing = 4
        return s
    }

    private func updateDirectoryLabel() {
        directoryLabel.stringValue = templateDirectory.path
        directoryLabel.toolTip = templateDirectory.path
    }

    // MARK: - Image loading

    @objc func openImage() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.png, .jpeg, .bmp, .tiff, .heic]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        load(url: url)
    }

    func load(url: URL) {
        // Load through ImageIO to get the real pixel size (NSImage reports points on Retina).
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil),
              let buf = PixelBuffer(image: img) else {
            alert("无法读取图片：\(url.lastPathComponent)")
            return
        }
        image = img
        pixels = buf
        canvas.image = img
        canvas.searchRect = PixelRect(x: 0, y: 0, w: img.width, h: img.height)
        canvas.templateRect = nil
        canvas.matchRect = nil
        // Fit large screenshots into the visible area.
        let visible = scrollView.contentSize
        canvas.zoom = min(1, visible.width / CGFloat(img.width), visible.height / CGFloat(img.height))
        window?.title = "图片区块匹配工具 - \(url.lastPathComponent) (\(img.width)x\(img.height))"
        resultLabel.stringValue = " "
        syncFieldsFromCanvas()
        regenerate()
    }

    // MARK: - Zoom

    @objc func zoomIn() { canvas.zoom = min(canvas.zoom * 1.5, 32) }
    @objc func zoomOut() { canvas.zoom = max(canvas.zoom / 1.5, 0.1) }
    @objc func zoomReset() { canvas.zoom = 1 }

    // MARK: - Selection sync

    @objc private func blockStepperChanged() {
        blockField.integerValue = blockStepper.integerValue
        regenerate()
    }

    @objc private func chooseDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.directoryURL = templateDirectory
        panel.message = "选择模板图片目录（项目内为 \(Self.repoTemplatePath)）"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        templateDirectory = url
    }

    private func selectionChangedFromCanvas() {
        canvas.matchRect = nil
        syncFieldsFromCanvas()
        regenerate()
    }

    private func syncFieldsFromCanvas() {
        fill(searchFields, canvas.searchRect)
        fill(templateFields, canvas.templateRect)
    }

    private func fill(_ fields: [NSTextField], _ r: PixelRect?) {
        let values = r.map { [$0.x, $0.y, $0.w, $0.h] }
        for (i, f) in fields.enumerated() {
            f.stringValue = values.map { String($0[i]) } ?? ""
        }
    }

    /// Reads a rect from the four fields, clamped to the image.
    private func rect(from fields: [NSTextField]) -> PixelRect? {
        guard let img = image else { return nil }
        let v = fields.map { Int($0.stringValue.trimmingCharacters(in: .whitespaces)) }
        guard let x = v[0], let y = v[1], let w = v[2], let h = v[3], w > 0, h > 0 else { return nil }
        let cx = min(max(x, 0), img.width - 1), cy = min(max(y, 0), img.height - 1)
        return PixelRect(x: cx, y: cy, w: min(w, img.width - cx), h: min(h, img.height - cy))
    }

    func controlTextDidEndEditing(_ obj: Notification) {
        guard let field = obj.object as? NSTextField else { return }
        if searchFields.contains(field) {
            canvas.searchRect = rect(from: searchFields)
        } else if templateFields.contains(field) {
            canvas.templateRect = rect(from: templateFields)
        } else if field == blockField {
            let b = min(max(blockField.integerValue, 1), 16)
            blockField.integerValue = b
            blockStepper.integerValue = b
        }
        canvas.matchRect = nil
        regenerate()
    }

    private func updateHover(_ p: (Int, Int)?) {
        guard let p, let buf = pixels else { hoverLabel.stringValue = " "; return }
        let c = buf.rgb(p.0, p.1)
        hoverLabel.stringValue = String(format: "坐标 %d, %d   颜色 #%02X%02X%02X   缩放 %.0f%%", p.0, p.1, c.0, c.1, c.2, canvas.zoom * 100)
    }

    // MARK: - Code generation

    private var blockSize: Int { min(max(blockField.integerValue, 1), 16) }

    private var similarity: Double? {
        guard let s = Double(similarityField.stringValue), s > 0, s <= 1 else { return nil }
        return s
    }

    private var propertyName: String {
        let cleaned = propertyField.stringValue.filter { $0.isLetter || $0.isNumber || $0 == "_" }
        if let first = cleaned.first, first.isLetter || first == "_" { return cleaned }
        return "Template" + cleaned
    }

    /// File name without extension, restricted to what BlockTemplateSchema accepts ([A-Za-z0-9_]).
    private var imageName: String {
        let cleaned = imageNameField.stringValue.filter { ($0.isASCII && ($0.isLetter || $0.isNumber)) || $0 == "_" }
        return cleaned.isEmpty ? "template" : cleaned
    }

    private var templateFileURL: URL { templateDirectory.appendingPathComponent("\(imageName).png") }

    private func currentTemplate() -> BlockTemplate? {
        guard let buf = pixels, let t = canvas.templateRect else { return nil }
        return BlockTemplate.make(from: buf, x: t.x, y: t.y, w: t.w, h: t.h, block: blockSize)
    }

    /// Rebuilds the info text and the generated Kotlin code.
    private func regenerate() {
        guard image != nil else { codeView.string = ""; return }
        guard let s = canvas.searchRect else { infoLabel.stringValue = "请用左键框选搜索区域（红框）。"; codeView.string = ""; return }
        guard let t = canvas.templateRect, let tpl = currentTemplate() else {
            infoLabel.stringValue = "请用右键框选目标图片（绿框），宽高至少为一个区块（\(blockSize)px）。"
            codeView.string = ""
            return
        }
        var info = "目标图片 \(t.w)x\(t.h)，区块 \(tpl.block)px → \(tpl.cols)x\(tpl.rows) = \(tpl.means.count) 个区块"
        if tpl.pixelWidth != t.w || tpl.pixelHeight != t.h {
            info += "\n实际参与匹配 \(tpl.pixelWidth)x\(tpl.pixelHeight)，右侧/底部多余像素被忽略。"
        }
        if s.w < tpl.pixelWidth || s.h < tpl.pixelHeight {
            info += "\n⚠️ 搜索区域比目标图片小，无法匹配。"
        }
        if similarity == nil {
            info += "\n⚠️ 相似度需在 0~1 之间。"
        }
        if FileManager.default.fileExists(atPath: templateFileURL.path) {
            info += "\n目录中已有 \(imageName).png，保存时会覆盖。"
        }
        infoLabel.stringValue = info

        let name = nameField.stringValue.replacingOccurrences(of: "\"", with: "\\\"")
        let sim = similarity.map { String($0) } ?? "0.95"
        codeView.string = """
        // Interface declaration
        val \(propertyName): BlockTemplateSchema

        // Implementation: reads \(imageName).png (\(t.w)x\(t.h), captured at \(t.x), \(t.y)) from the templates directory
        override val \(propertyName) = BlockTemplateSchema.fromImage(
            \(s.x), \(s.y), \(s.x2), \(s.y2), "\(imageName)", \(tpl.block), \(sim), "\(name)"
        )

        // Usage
        // import com.coc.suncode.jar.code.colorschema.BlockTemplateSchema
        // import com.coc.suncode.jar.code.universal.colors.findBlockTemplate
        // findBlockTemplate(MyColors.\(propertyName))?.let { match -> val (cx, cy) = match.center(MyColors.\(propertyName)) }
        """
    }

    // MARK: - Actions

    @objc func testMatch() {
        guard let buf = pixels, let s = canvas.searchRect, let tpl = currentTemplate() else {
            alert("请先框选搜索区域和目标图片。")
            return
        }
        resultLabel.stringValue = "匹配中…"
        let threshold = similarity ?? 0.95
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Matcher.search(buf: buf, tpl: tpl, x1: s.x, y1: s.y, x2: s.x2, y2: s.y2)
            DispatchQueue.main.async { [weak self] in
                self?.showResult(result, tpl: tpl, threshold: threshold)
            }
        }
    }

    private func showResult(_ result: MatchResult?, tpl: BlockTemplate, threshold: Double) {
        guard let r = result else {
            canvas.matchRect = nil
            resultLabel.stringValue = "搜索区域比目标图片小，无法匹配。"
            return
        }
        canvas.matchRect = PixelRect(x: r.x, y: r.y, w: tpl.pixelWidth, h: tpl.pixelHeight)
        var text = String(format: "最佳位置 %d, %d   相似度 %.4f   %@\n", r.x, r.y, r.score, r.score >= threshold ? "✅ 命中" : "❌ 低于阈值")
        if let second = r.secondScore {
            // A large gap between the best and the runner-up means the threshold is easy to pick.
            text += String(format: "次佳（不重叠）相似度 %.4f，建议阈值取在两者之间", second)
            if second >= threshold { text += "\n⚠️ 次佳位置也超过阈值，可能误判，请调高阈值或换更有特征的目标。" }
        } else {
            text += "搜索区域内没有其他不重叠的位置。"
        }
        text += String(format: "\n耗时 %.1f ms（Mac 上全像素精确搜索）", r.elapsedMs)
        resultLabel.stringValue = text
    }

    private func copyCode() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(codeView.string, forType: .string)
    }

    /// Saves the template crop as <imageName>.png into the template directory and copies the code.
    @objc func saveTemplateImage() {
        guard let img = image, let t = canvas.templateRect, !codeView.string.isEmpty else {
            alert("请先框选搜索区域和目标图片。")
            return
        }
        guard let cropped = img.cropping(to: CGRect(x: t.x, y: t.y, width: t.w, height: t.h)),
              let srgb = Self.sRGBCopy(of: cropped) else {
            alert("裁剪图片失败。")
            return
        }
        let url = templateFileURL
        do {
            try FileManager.default.createDirectory(at: templateDirectory, withIntermediateDirectories: true)
        } catch {
            alert("无法创建目录：\(templateDirectory.path)\n\(error.localizedDescription)")
            return
        }
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            alert("无法写入：\(url.path)")
            return
        }
        CGImageDestinationAddImage(dest, srgb, nil)
        guard CGImageDestinationFinalize(dest) else {
            alert("保存失败：\(url.path)")
            return
        }
        copyCode()
        regenerate()
        resultLabel.stringValue = "已保存 \(url.lastPathComponent) 到模板目录，代码已复制到剪贴板。"
    }

    /// Re-tags the pixels as sRGB without converting them, so Android decodes the same values.
    private static func sRGBCopy(of image: CGImage) -> CGImage? {
        guard let buf = PixelBuffer(image: image) else { return nil }
        var data = buf.data
        return data.withUnsafeMutableBytes { raw -> CGImage? in
            CGContext(
                data: raw.baseAddress, width: buf.width, height: buf.height, bitsPerComponent: 8,
                bytesPerRow: buf.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            )?.makeImage()
        }
    }

    private func alert(_ message: String) {
        let a = NSAlert()
        a.messageText = message
        a.runModal()
    }
}
