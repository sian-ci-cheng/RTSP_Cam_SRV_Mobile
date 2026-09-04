import SwiftUI
import AVFoundation

struct ContentView: View {
    @StateObject private var viewModel = StreamViewModel()

    var body: some View {
        VStack(spacing: 16) {
            CameraPreview(session: viewModel.camera.session)
                .frame(height: 300)
                .background(Color.black)

            Text(viewModel.statusText)
                .font(.headline)

            if viewModel.isRunning {
                Label(viewModel.isAudioEnabled ? "Audio ON" : "Audio OFF (mic permission denied)",
                      systemImage: viewModel.isAudioEnabled ? "mic.fill" : "mic.slash.fill")
                    .font(.subheadline)
                    .foregroundStyle(viewModel.isAudioEnabled ? .primary : .secondary)
            } else {
                Toggle("Record Audio", isOn: $viewModel.wantsAudio)
            }

            if viewModel.isRunning {
                if viewModel.isRecordingEnabled {
                    Label(viewModel.recordingErrorText ?? "Recording to file", systemImage: "record.circle")
                        .font(.subheadline)
                        .foregroundStyle(viewModel.recordingErrorText == nil ? .red : .secondary)
                }
            } else {
                Toggle("Record to Local File", isOn: $viewModel.wantsLocalRecording)
            }

            Picker("Resolution", selection: $viewModel.resolution) {
                ForEach(viewModel.availableResolutions) { resolution in
                    Text(resolution.rawValue).tag(resolution)
                }
            }
            .pickerStyle(.segmented)
            .disabled(viewModel.isRunning)

            if !viewModel.addresses.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Connect a player to one of these:")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    ForEach(viewModel.addresses, id: \.self) { address in
                        Text("rtsp://\(address):8554/")
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                .padding()
                .background(Color(.secondarySystemBackground))
                .cornerRadius(12)
            }

            Button(viewModel.isRunning ? "Stop" : "Start") {
                viewModel.toggle()
            }
            .buttonStyle(.borderedProminent)
            .tint(viewModel.isRunning ? .red : .green)

            Spacer()
        }
        .padding()
    }
}

final class StreamViewModel: ObservableObject {
    let camera = CameraCaptureManager()
    private var server: RTSPServer?
    private var recorder: LocalRecorder?

    @Published var isRunning = false
    @Published var statusText = "Stopped"
    @Published var addresses: [String] = []
    @Published var isAudioEnabled = false
    @Published var wantsAudio = true
    @Published var wantsLocalRecording = false
    @Published var isRecordingEnabled = false
    @Published var recordingErrorText: String?
    @Published var resolution: StreamResolution
    let availableResolutions: [StreamResolution]

    init() {
        let supported = CameraCaptureManager.supportedResolutions()
        availableResolutions = supported
        resolution = supported.contains(.hd720p) ? .hd720p : (supported.first ?? .hd720p)
    }

    func toggle() {
        isRunning ? stop() : start()
    }

    private func start() {
        camera.requestAccessAndConfigure(resolution: resolution, wantsAudio: wantsAudio) { [weak self] granted in
            guard let self else { return }
            DispatchQueue.main.async {
                guard granted else {
                    self.statusText = "Camera access denied"
                    return
                }
                self.camera.start()

                let server = RTSPServer(port: 8554, encoder: self.camera.encoder, audioEncoder: self.camera.audio.encoder)
                server.onStatusChange = { [weak self] status in
                    DispatchQueue.main.async { self?.statusText = status }
                }
                server.start()
                self.server = server

                self.addresses = NetworkUtils.ipv4Address().map { $0.address }
                self.isRunning = true
                self.isAudioEnabled = self.camera.isAudioEnabled
                self.statusText = "Starting..."

                // A failure here (disk full, writer error) only ever affects the recording, never
                // the live stream: LocalRecorder is just another independent subscriber of the
                // same encoders. Pass audioEncoder only when audio actually started -- otherwise
                // LocalRecorder would wait forever for an audio track that will never arrive.
                self.recordingErrorText = nil
                self.isRecordingEnabled = false
                if self.wantsLocalRecording {
                    let url = Self.makeRecordingURL()
                    let recorder = LocalRecorder(
                        outputURL: url,
                        videoEncoder: self.camera.encoder,
                        audioEncoder: self.isAudioEnabled ? self.camera.audio.encoder : nil
                    )
                    recorder.onError = { [weak self] message in
                        DispatchQueue.main.async { self?.recordingErrorText = message }
                    }
                    if recorder.start() {
                        self.recorder = recorder
                        self.isRecordingEnabled = true
                    } else {
                        self.recordingErrorText = "Recording failed to start"
                    }
                }
            }
        }
    }

    private func stop() {
        server?.stop()
        server = nil
        recorder?.stop()
        recorder = nil
        camera.stop()
        isRunning = false
        isAudioEnabled = false
        isRecordingEnabled = false
        statusText = "Stopped"
    }

    private static func makeRecordingURL() -> URL {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return directory.appendingPathComponent("stream_\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}
