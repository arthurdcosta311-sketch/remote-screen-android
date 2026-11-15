import SwiftUI
import UIKit

@main
struct RemoteScreenApp: App {
    @StateObject private var store = PhoneStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}

// MARK: - Phone list

extension View {
    /// The ADB screen exists only in builds that linked libadb.
    @ViewBuilder
    func adbSheet(phone: Binding<PairedPhone?>) -> some View {
        #if ADB_MOBILE
        sheet(item: phone) { entry in AdbView(phone: entry) }
        #else
        self
        #endif
    }
}

struct SessionTarget: Identifiable {
    let phone: PairedPhone
    let mode: StreamMode
    var id: String { phone.id + mode.rawValue }
}

struct ContentView: View {
    @EnvironmentObject private var store: PhoneStore
    @State private var showPair = false
    @State private var target: SessionTarget?
    @State private var shellPhone: PairedPhone?
    @State private var adbPhone: PairedPhone?
    @State private var editing: PairedPhone?
    @State private var editHost = ""

    var body: some View {
        NavigationStack {
            List {
                if store.phones.isEmpty {
                    Section {
                        Text("No paired phones yet. Open Remote Screen on your Android phone, then tap + and enter the phone's IP address.")
                            .foregroundStyle(.secondary)
                    }
                }
                ForEach(store.phones) { phone in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(phone.name).font(.headline)
                        Text(phone.host).font(.subheadline).foregroundStyle(.secondary)
                        HStack(spacing: 8) {
                            Button("Mirror") { target = SessionTarget(phone: phone, mode: .mirror) }
                                .buttonStyle(.borderedProminent)
                            Button("Second screen") { target = SessionTarget(phone: phone, mode: .virtual) }
                                .buttonStyle(.bordered)
                            Button("Console") { shellPhone = phone }
                                .buttonStyle(.bordered)
                            #if ADB_MOBILE
                            Button("ADB") { adbPhone = phone }
                                .buttonStyle(.bordered)
                            #endif
                        }
                        .font(.footnote)
                    }
                    .padding(.vertical, 4)
                    .swipeActions {
                        Button(role: .destructive) {
                            store.remove(phone)
                        } label: {
                            Label("Forget", systemImage: "trash")
                        }
                        Button {
                            editHost = phone.host
                            editing = phone
                        } label: {
                            Label("Address", systemImage: "network")
                        }
                        .tint(.blue)
                    }
                }
            }
            .navigationTitle("Remote Screen")
            .toolbar {
                Button { showPair = true } label: { Image(systemName: "plus") }
            }
            .sheet(isPresented: $showPair) { PairView() }
            .sheet(item: $shellPhone) { phone in ShellView(phone: phone) }
            .adbSheet(phone: $adbPhone)
            .fullScreenCover(item: $target) { t in
                SessionView(phone: t.phone, mode: t.mode)
            }
            .alert(
                "Phone address",
                isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })
            ) {
                TextField("192.168.0.20", text: $editHost)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                Button("Save") {
                    if let phone = editing { store.updateHost(phone, host: editHost) }
                    editing = nil
                }
                Button("Cancel", role: .cancel) { editing = nil }
            } message: {
                Text("Use this when the phone's IP address changed.")
            }
        }
    }
}

// MARK: - Pairing

@MainActor
final class PairingFlow: ObservableObject {
    enum Step {
        case form
        case connecting
        case confirm(code: String, phone: String)
        case failed(String)
    }

    @Published var step: Step = .form
    private var task: Task<Void, Never>?

    func start(host: String, store: PhoneStore, onDone: @escaping () -> Void) {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        step = .connecting
        task?.cancel()
        task = Task {
            do {
                let phone = try await PairingClient.pair(
                    host: trimmed,
                    receiverName: UIDevice.current.name
                ) { [weak self] code, name in
                    self?.step = .confirm(code: code, phone: name)
                }
                store.add(phone)
                onDone()
            } catch {
                if !Task.isCancelled { step = .failed(error.localizedDescription) }
            }
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
        step = .form
    }
}

struct PairView: View {
    @EnvironmentObject private var store: PhoneStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var flow = PairingFlow()
    @State private var host = ""

    var body: some View {
        NavigationStack {
            Form {
                switch flow.step {
                case .form, .failed:
                    Section("Phone address") {
                        TextField("192.168.0.20", text: $host)
                            .keyboardType(.numbersAndPunctuation)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                    Section {
                        Text("Keep Remote Screen open on the phone (same Wi-Fi or hotspot). The address is shown at the top of the phone app.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    if case .failed(let message) = flow.step {
                        Section { Text(message).foregroundStyle(.red) }
                    }
                    Section {
                        Button("Pair") { flow.start(host: host, store: store) { dismiss() } }
                            .disabled(host.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                case .connecting:
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Connecting…")
                        }
                    }
                case .confirm(let code, let phone):
                    Section("Pairing code") {
                        Text(code)
                            .font(.system(size: 44, weight: .bold, design: .monospaced))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    Section {
                        HStack(alignment: .top, spacing: 12) {
                            ProgressView()
                            Text("\(phone) now shows \"Pair with …?\". Make sure it shows exactly these six digits, then tap Pair on the phone. If the digits differ, tap Deny.")
                        }
                    }
                }
            }
            .navigationTitle("Pair a phone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        flow.cancel()
                        dismiss()
                    }
                }
            }
        }
        .interactiveDismissDisabled()
    }
}

// MARK: - Video surface

final class VideoHostView: UIView {
    private let videoLayer: CALayer

    init(layer videoLayer: CALayer) {
        self.videoLayer = videoLayer
        super.init(frame: .zero)
        backgroundColor = .black
        layer.addSublayer(videoLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.frame = bounds
        CATransaction.commit()
    }
}

struct VideoSurface: UIViewRepresentable {
    let renderer: VideoRenderer

    func makeUIView(context: Context) -> VideoHostView {
        VideoHostView(layer: renderer.layer)
    }

    func updateUIView(_ uiView: VideoHostView, context: Context) {}
}

// MARK: - Live session

struct SessionView: View {
    @StateObject private var model: SessionModel
    @Environment(\.dismiss) private var dismiss
    @State private var dragging = false
    @State private var showText = false
    @State private var text = ""

    init(phone: PairedPhone, mode: StreamMode) {
        _model = StateObject(wrappedValue: SessionModel(phone: phone, mode: mode))
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color.black
                GeometryReader { geo in
                    ZStack {
                        VideoSurface(renderer: model.renderer)
                            .frame(width: geo.size.width, height: geo.size.height)
                        // A SwiftUI layer on top, so touches are never swallowed by the
                        // UIView that hosts the video layer.
                        Color.clear
                            .contentShape(Rectangle())
                            .gesture(dragGesture(in: geo.size))
                    }
                }
                if !model.isLive || model.ended {
                    VStack(spacing: 12) {
                        if !model.ended { ProgressView().tint(.white) }
                        Text(model.status)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 24)
                    }
                    .allowsHitTesting(false)
                }
            }
            if model.isLive && !model.touchEnabled && !model.keysEnabled {
                Text("View only. Enable Remote Screen input in the phone's Accessibility settings to control it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
                    .padding(.top, 6)
            }
            if showText { textBar }
            controls
        }
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            model.start()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            model.stop()
        }
    }

    private func dragGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard model.touchEnabled, let p = model.normalize(value.location, in: size) else { return }
                if dragging {
                    model.sendTouch("move", p)
                } else {
                    dragging = true
                    model.sendTouch("down", p)
                }
            }
            .onEnded { value in
                guard dragging else { return }
                dragging = false
                if let p = model.normalize(value.location, in: size) {
                    model.sendTouch("move", p, force: true)
                }
                model.sendTouch("up", nil)
            }
    }

    private var textBar: some View {
        HStack {
            TextField(model.mode == .virtual ? "Message for the second screen" : "Type into the focused field", text: $text)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.send)
                .onSubmit(sendText)
            Button("Send", action: sendText)
                .disabled(text.isEmpty || !model.keysEnabled)
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private func sendText() {
        guard !text.isEmpty else { return }
        model.sendText(text)
        text = ""
    }

    private var controls: some View {
        HStack(spacing: 0) {
            keyButton("chevron.backward", "back")
            keyButton("circle", "home")
            keyButton("square", "recents")
            keyButton("bell", "notifications")
            keyButton("lock", "lock")
            Button {
                showText.toggle()
            } label: {
                Image(systemName: "keyboard").frame(maxWidth: .infinity, minHeight: 44)
            }
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark").frame(maxWidth: .infinity, minHeight: 44)
            }
            .tint(.red)
        }
        .padding(.vertical, 4)
        .background(Color(white: 0.1))
    }

    private func keyButton(_ icon: String, _ key: String) -> some View {
        Button {
            model.sendKey(key)
        } label: {
            Image(systemName: icon).frame(maxWidth: .infinity, minHeight: 44)
        }
        .disabled(!model.keysEnabled)
    }
}
