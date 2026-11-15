import AppKit

final class DropZone: NSView {
    var accept: ((URL) -> Void)?
    var sourceMode = false
    func accepts(_ url: URL) -> Bool { sourceMode ? (((try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true) || url.pathExtension.lowercased() == "zip") : url.pathExtension.lowercased() == "jar" }
    let label = NSTextField(labelWithString: "将 update.jar 拖到这里，或点击选择文件")
    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.cornerRadius = 18
        layer?.borderWidth = 1
        layer?.borderColor = NSColor.controlAccentColor.withAlphaComponent(0.35).cgColor
        layer?.backgroundColor = NSColor.controlAccentColor.withAlphaComponent(0.08).cgColor
        setAccessibilityElement(true)
        setAccessibilityRole(.button)
        setAccessibilityLabel("选择更新 JAR，或拖入文件")
        registerForDraggedTypes([.fileURL])
        label.alignment = .center
        label.lineBreakMode = .byTruncatingMiddle
        label.font = .systemFont(ofSize: 15, weight: .medium)
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([label.centerYAnchor.constraint(equalTo: centerYAnchor), label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16), label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16)])
    }
    required init?(coder: NSCoder) { fatalError() }
    func urls(_ info: NSDraggingInfo) -> [URL] {
        (info.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
    }
    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        let files = urls(sender)
        return files.count == 1 && accepts(files[0]) ? .copy : []
    }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let files = urls(sender)
        guard files.count == 1, accepts(files[0]) else { return false }
        accept?(files[0]); return true
    }
    override func accessibilityPerformPress() -> Bool { chooseFile(); return true }
    override func mouseDown(with event: NSEvent) { chooseFile() }
    func chooseFile() {
        let panel = NSOpenPanel(); panel.allowedFileTypes = sourceMode ? ["zip"] : ["jar"]; panel.canChooseDirectories = sourceMode; panel.canChooseFiles = true; panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let file = panel.url { accept?(file) }
    }
}

final class Publisher: NSObject, NSApplicationDelegate, NSTextFieldDelegate, NSTextViewDelegate, NSWindowDelegate {
    var window: NSWindow!
    let drop = DropZone(frame: .zero)
    let sourceDrop = DropZone(frame: .zero)
    var sourceInput: URL?
    let version = NSTextField(string: "")
    let notes = NSTextView()
    let log = NSTextView()
    let status = NSTextField(labelWithString: "选择更新包并填写信息，然后预检。")
    let check = NSButton(title: "本地打包并预检", target: nil, action: nil)
    let publish = NSButton(title: "发布更新并同步官网", target: nil, action: nil)
    var jar: URL?
    var task: Process?
    var temporary: URL?
    var checked = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        let menu = NSMenu(); let appMenuItem = NSMenuItem(); menu.addItem(appMenuItem)
        let appMenu = NSMenu(); appMenu.addItem(withTitle: "退出发布助手", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"); appMenuItem.submenu = appMenu; NSApp.mainMenu = menu
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 860, height: 780), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "ØchreCL 发布助手"
        window.delegate = self
        window.minSize = NSSize(width: 720, height: 700)
        let root = NSStackView(); root.orientation = .vertical; root.alignment = .leading; root.spacing = 14
        root.translatesAutoresizingMaskIntoConstraints = false
        let content = window.contentView!; content.addSubview(root)
        NSLayoutConstraint.activate([root.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24), root.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24), root.topAnchor.constraint(equalTo: content.topAnchor, constant: 22), root.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -22)])
        let title = NSTextField(labelWithString: "发布启动器更新"); title.font = .systemFont(ofSize: 25, weight: .bold)
        root.addArrangedSubview(title)
        let description = NSTextField(wrappingLabelWithString: "拖入源码和 JAR → 校验对应关系 → 打包四平台 → 发布。使用已授权的 Fishyra GitHub 账号，无需复制令牌。")
        description.textColor = .secondaryLabelColor; root.addArrangedSubview(description)
        let inputFiles = NSStackView(views: [drop, sourceDrop]); inputFiles.orientation = .horizontal; inputFiles.distribution = .fillEqually; inputFiles.spacing = 12; root.addArrangedSubview(inputFiles); inputFiles.widthAnchor.constraint(equalTo: root.widthAnchor).isActive = true; drop.heightAnchor.constraint(equalToConstant: 72).isActive = true
        drop.accept = { [weak self] url in
            guard self?.task == nil else { return }
            self?.jar = url; self?.drop.label.stringValue = "已选择：" + url.lastPathComponent
            self?.drop.toolTip = url.path; self?.invalidate()
        }
        sourceDrop.sourceMode = true; sourceDrop.label.stringValue = "拖入源码文件夹 / ZIP，或点击选择"; sourceDrop.setAccessibilityLabel("选择与更新包对应的源码文件夹或 ZIP")
        sourceDrop.heightAnchor.constraint(equalToConstant: 72).isActive = true
        sourceDrop.accept = { [weak self] url in
            guard self?.task == nil else { return }
            self?.sourceInput = url; self?.sourceDrop.label.stringValue = "已选择源码：" + url.lastPathComponent; self?.sourceDrop.toolTip = url.path; self?.invalidate()
        }
        let row = NSStackView(); row.spacing = 12
        row.addArrangedSubview(NSTextField(labelWithString: "版本号")); version.placeholderString = "例如 0.3.0-alpha.5"; version.delegate = self
        row.addArrangedSubview(version); root.addArrangedSubview(row); row.widthAnchor.constraint(equalTo: root.widthAnchor).isActive = true
        let hint = NSTextField(wrappingLabelWithString: "使用本机运行环境模板，重打包 macOS ARM64 / Intel、Windows x64、Linux x64。预检会核对 JAR 内的源码指纹并准备本地源码提交，发布时同步公开完整源码。Java 或原生依赖有变化时，需先更新模板。")
        hint.font = .systemFont(ofSize: 12); hint.textColor = .secondaryLabelColor; root.addArrangedSubview(hint)
        root.addArrangedSubview(NSTextField(labelWithString: "更新说明与已知限制"))
        notes.isRichText = false; notes.font = .systemFont(ofSize: 13); notes.delegate = self
        notes.textContainerInset = NSSize(width: 10, height: 10)
        let notesScroll = scroll(notes); root.addArrangedSubview(notesScroll); notesScroll.widthAnchor.constraint(equalTo: root.widthAnchor).isActive = true; notesScroll.heightAnchor.constraint(equalToConstant: 145).isActive = true
        status.textColor = .secondaryLabelColor; status.lineBreakMode = .byTruncatingTail
        root.addArrangedSubview(status)
        log.isEditable = false; log.isRichText = false; log.font = .monospacedSystemFont(ofSize: 11, weight: .regular); log.textContainerInset = NSSize(width: 10, height: 10)
        let logScroll = scroll(log); root.addArrangedSubview(logScroll); logScroll.widthAnchor.constraint(equalTo: root.widthAnchor).isActive = true; logScroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 125).isActive = true
        logScroll.setContentHuggingPriority(.defaultLow, for: .vertical)
        let buttons = NSStackView(); buttons.spacing = 10
        check.target = self; check.action = #selector(preflight); check.bezelStyle = .rounded
        publish.target = self; publish.action = #selector(confirmPublish); publish.bezelStyle = .rounded; publish.isEnabled = false
        buttons.addArrangedSubview(check); buttons.addArrangedSubview(publish)
        root.addArrangedSubview(buttons)
        window.center(); window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        if ProcessInfo.processInfo.environment["OCHRE_PUBLISHER_UI_SMOKE"] == "1" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                content.layoutSubtreeIfNeeded()
                let valid = self.sourceDrop.sourceMode && self.sourceDrop.accepts(FileManager.default.temporaryDirectory) && !self.sourceDrop.accepts(URL(fileURLWithPath: "/tmp/example.jar")) && !self.publish.isEnabled && content.bounds.contains(self.check.convert(self.check.bounds, to: content))
                if let bitmap = content.bitmapImageRepForCachingDisplay(in: content.bounds) { content.cacheDisplay(in: content.bounds, to: bitmap); if let data = bitmap.representation(using: .png, properties: [:]) { try? data.write(to: URL(fileURLWithPath: "/tmp/ochre-publisher-source-preview.png")) } }
                print(valid ? "PASS: paired source/JAR pickers, disabled publish gate and visible footer" : "FAIL: publisher layout or source picker")
                fflush(stdout); exit(valid ? 0 : 1)
            }
        }

    }
    func scroll(_ text: NSTextView) -> NSScrollView {
        let view = NSScrollView(); view.documentView = text; view.hasVerticalScroller = true; view.borderType = .noBorder
        view.wantsLayer = true; view.layer?.cornerRadius = 12; view.layer?.masksToBounds = true
        text.minSize = NSSize(width: 0, height: 100); text.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        text.isVerticallyResizable = true; text.isHorizontallyResizable = false; text.autoresizingMask = [.width]
        text.textContainer?.widthTracksTextView = true
        return view
    }
    func invalidate() { checked = false; publish.isEnabled = false; status.stringValue = "信息已变更，请重新预检。" }
    func controlTextDidChange(_ obj: Notification) { invalidate() }
    func textDidChange(_ notification: Notification) { invalidate() }
    func append(_ text: String) {
        log.textStorage?.append(NSAttributedString(string: text, attributes: [.font: NSFont.monospacedSystemFont(ofSize: 11, weight: .regular), .foregroundColor: NSColor.labelColor]))
        log.scrollToEndOfDocument(nil)
    }
    @objc func preflight() { start(publishing: false) }
    @objc func confirmPublish() {
        guard checked, task == nil else { return }
        let alert = NSAlert(); alert.messageText = "发布 ØchreCL \(version.stringValue)？"
        alert.informativeText = "将把所选 JAR、四平台安装包和校验文件公开到 Fishyra/OchreCL，并推送官网更新提示。源码和 JAR 的构建输入指纹已核对。请确认已完成实际功能测试。将发布四种新版完整安装包并更新官网直链。"
        alert.addButton(withTitle: "发布"); alert.addButton(withTitle: "取消")
        alert.beginSheetModal(for: window) { [weak self] result in
            if result == .alertFirstButtonReturn { self?.start(publishing: true) }
        }
    }
    func start(publishing: Bool) {
        guard task == nil else { return }
        guard let jar = jar, let sourceInput = sourceInput, !version.stringValue.isEmpty, !notes.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            status.stringValue = "请选择 JAR 和对应源码、填写版本号和更新说明。"; return
        }
        guard let backend = Bundle.main.url(forResource: "publish_update", withExtension: "py"), let config = Bundle.main.url(forResource: "config", withExtension: "json") else { return }
        do {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("ochre-publisher-" + UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let noteFile = directory.appendingPathComponent("notes.txt"); try notes.string.write(to: noteFile, atomically: true, encoding: .utf8); temporary = directory
            let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
            process.arguments = [backend.path, "--full", "--config", config.path, "--jar", jar.path, "--source", sourceInput.path, "--version", version.stringValue, "--notes", noteFile.path] + (publishing ? ["--publish"] : [])
            var env = ProcessInfo.processInfo.environment
            env["PATH"] = NSHomeDirectory() + "/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
            env["PYTHONUNBUFFERED"] = "1"; process.environment = env
            let pipe = Pipe(); process.standardOutput = pipe; process.standardError = pipe
            pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
                let data = handle.availableData
                if data.isEmpty { handle.readabilityHandler = nil; return }
                let text = String(decoding: data, as: UTF8.self)
                DispatchQueue.main.async { self?.append(text) }
            }
            process.terminationHandler = { [weak self] completed in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.task = nil; self.version.isEditable = true; self.notes.isEditable = true; self.check.isEnabled = true
                    self.checked = completed.terminationStatus == 0 && !publishing; self.publish.isEnabled = self.checked
                    self.status.stringValue = completed.terminationStatus == 0 ? (publishing ? "发布完成，官网部署中。" : "预检通过，请核对日志后发布。") : "未完成，请查看日志。已发布的版本不会被覆盖。"
                    if let directory = self.temporary { try? FileManager.default.removeItem(at: directory) }; self.temporary = nil
                }
            }
            log.string = ""; check.isEnabled = false; publish.isEnabled = false; version.isEditable = false; notes.isEditable = false
            status.stringValue = publishing ? "发布中，请勿关闭窗口…" : "正在本地打包并校验，请稍候…"
            task = process; try process.run()
        } catch {
            task = nil; check.isEnabled = true; version.isEditable = true; notes.isEditable = true
            status.stringValue = "无法运行：" + error.localizedDescription
        }
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if task != nil { status.stringValue = "操作执行中，请等待结束后关闭。"; return false }; return true
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if task != nil {
            let alert = NSAlert(); alert.messageText = "正在执行操作"; alert.informativeText = "请等待操作结束后退出，避免中断上传。"; alert.runModal(); return .terminateCancel
        }
        return .terminateNow
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

let application = NSApplication.shared
let delegate = Publisher()
application.delegate = delegate
application.setActivationPolicy(.regular)
application.run()
