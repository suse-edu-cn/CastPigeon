import SwiftUI
import UserNotifications

@main
struct CastPigeonMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    var body: some Scene {
        WindowGroup {
            MainView().frame(minWidth: 640, minHeight: 480)
        }.windowResizability(.contentMinSize)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }
}

final class MainViewModel: ObservableObject {
    @Published var connectionState: ConnectionState = .idle
    @Published var discoveredDevices: [DiscoveredDevice] = []
    @Published var messages: [LoggedMessage] = []
    @Published var sendText: String = ""
    @Published var manualIP: String = ""
    @Published var manualPort: String = "9876"
    @Published var simulatedAppName: String = "WeChat"
    @Published var simulatedTitle: String = "New Message"
    @Published var simulatedContent: String = "Hello from CastPigeon!"
    @Published var isScanning: Bool = false
    @Published var pairRequestDeviceName: String? = nil

    init() { NetworkManager.shared.delegate = self }

    func startScanning() { isScanning = true; NetworkManager.shared.startBrowsingAndBroadcasting() }
    func stopScanning() { isScanning = false; NetworkManager.shared.stopAll() }
    func connectToDevice(_ device: DiscoveredDevice) { NetworkManager.shared.connectToDevice(device) }
    func connectToManualIP() {
        guard !manualIP.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        NetworkManager.shared.connectToIP(manualIP.trimmingCharacters(in: .whitespaces), port: UInt16(manualPort) ?? 9876)
    }
    func disconnect() { NetworkManager.shared.disconnect() }
    func sendMessage() {
        guard !sendText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        NetworkManager.shared.sendTestMessage(sendText); sendText = ""
    }
    func sendSimulatedNotification() { NetworkManager.shared.sendSimulatedNotification(appName: simulatedAppName, title: simulatedTitle, content: simulatedContent) }
    func sendPing() { NetworkManager.shared.sendPing() }
    func toggleScanning() { isScanning ? stopScanning() : startScanning() }
    func acceptPair() { NetworkManager.shared.acceptPairRequest(); pairRequestDeviceName = nil }
    func rejectPair() { NetworkManager.shared.rejectPairRequest(); pairRequestDeviceName = nil }
}

extension MainViewModel: NetworkManagerDelegate {
    func networkManagerDidUpdateState(_ state: ConnectionState) { connectionState = state }
    func networkManagerDidDiscoverDevice(_ device: DiscoveredDevice) { discoveredDevices.append(device) }
    func networkManagerDidRemoveDevice(_ device: DiscoveredDevice) { discoveredDevices.removeAll { $0.id == device.id } }
    func networkManagerDidReceiveMessage(_ message: LoggedMessage) { messages.append(message) }
    func networkManagerDidReceivePairRequest(from deviceName: String) { pairRequestDeviceName = deviceName }
}

struct MainView: View {
    @StateObject private var viewModel = MainViewModel()
    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                leftPanel.frame(width: max(260, geo.size.width * 0.38))
                Divider()
                messageLogPanel
            }
        }
        .alert("Pairing Request", isPresented: Binding(get: { viewModel.pairRequestDeviceName != nil }, set: { if !$0 { viewModel.pairRequestDeviceName = nil } })) {
            Button("Accept") { viewModel.acceptPair() }
            Button("Reject", role: .cancel) { viewModel.rejectPair() }
        } message: {
            Text("\(viewModel.pairRequestDeviceName ?? "A device") wants to pair with you.")
        }
    }

    private var leftPanel: some View { ScrollView { VStack(alignment: .leading, spacing: 12) {
        HStack { Image(systemName: "bell.badge.fill").font(.title3).foregroundColor(.accentColor); Text("CastPigeon").font(.title3).fontWeight(.semibold); Spacer() }
        Divider(); scanControl; Divider(); discoveredDevices; Divider(); manualConnect; Divider(); simulatedNotification; Divider(); textMessage
    }.padding(12) } }

    private var scanControl: some View { VStack(alignment: .leading, spacing: 6) {
        HStack { Circle().fill(statusColor).frame(width:8,height:8); Text(viewModel.connectionState.rawValue).font(.subheadline); Spacer() }
        HStack(spacing:6) {
            Button(action: viewModel.toggleScanning) { Label(viewModel.isScanning ? "Stop" : "Start Scan", systemImage: viewModel.isScanning ? "stop.fill" : "antenna.radiowaves.left.and.right").frame(maxWidth:.infinity) }.buttonStyle(.borderedProminent).controlSize(.small)
            let c = viewModel.connectionState
            if !viewModel.isScanning && (c == .connected || c == .paired) {
                Button(action: viewModel.disconnect) { Text("Disconnect").frame(maxWidth:.infinity) }.buttonStyle(.bordered).controlSize(.small)
            }
            if c == .connected || c == .paired { Button(action: viewModel.sendPing) { Label("Ping", systemImage: "dot.radiowaves.left.and.right") }.buttonStyle(.bordered).controlSize(.small) }
        }
    } }

    private var statusColor: Color {
        switch viewModel.connectionState {
        case .idle: .gray; case .browsing, .broadcasting: .orange; case .connecting: .yellow; case .connected: .blue; case .paired: .green
        }
    }

    private var discoveredDevices: some View { VStack(alignment: .leading, spacing:4) {
        Text("Discovered Devices").font(.headline)
        if !viewModel.isScanning && viewModel.discoveredDevices.isEmpty { Text("Press 'Start Scan' to find devices").font(.caption).foregroundColor(.secondary) }
        else if viewModel.isScanning && viewModel.discoveredDevices.isEmpty { HStack(spacing:6) { ProgressView().scaleEffect(0.6).frame(width:12,height:12); Text("Scanning...").font(.caption).foregroundColor(.secondary) } }
        ForEach(viewModel.discoveredDevices) { dev in
            HStack { Image(systemName:"iphone.gen2").foregroundColor(.secondary); VStack(alignment:.leading,spacing:1) { Text(dev.name).font(.caption).fontWeight(.medium); Text(dev.displayInfo).font(.caption2).foregroundColor(.secondary).lineLimit(1) }
                Spacer(); Button("Connect") { viewModel.connectToDevice(dev) }.buttonStyle(.bordered).controlSize(.small).disabled(viewModel.connectionState == .connected || viewModel.connectionState == .paired) }.padding(.vertical,2)
        }
    } }

    private var manualConnect: some View { VStack(alignment:.leading,spacing:6) {
        Text("Manual Connect").font(.headline)
        HStack(spacing:4) { TextField("IP Address", text:$viewModel.manualIP).textFieldStyle(.roundedBorder).font(.caption); TextField("Port", text:$viewModel.manualPort).textFieldStyle(.roundedBorder).font(.caption).frame(width:60); Button("Connect") { viewModel.connectToManualIP() }.buttonStyle(.bordered).controlSize(.small).disabled(viewModel.manualIP.trimmingCharacters(in:.whitespaces).isEmpty) }
    } }

    private var simulatedNotification: some View { VStack(alignment:.leading,spacing:4) {
        Text("Simulate Notification").font(.headline)
        TextField("App Name", text:$viewModel.simulatedAppName).textFieldStyle(.roundedBorder).font(.caption)
        TextField("Title", text:$viewModel.simulatedTitle).textFieldStyle(.roundedBorder).font(.caption)
        TextField("Content", text:$viewModel.simulatedContent).textFieldStyle(.roundedBorder).font(.caption)
        Button("Send Notification") { viewModel.sendSimulatedNotification() }.buttonStyle(.bordered).controlSize(.small).disabled(viewModel.connectionState != .connected && viewModel.connectionState != .paired)
    } }

    private var textMessage: some View { VStack(alignment:.leading,spacing:4) {
        Text("Chat Message").font(.headline)
        HStack(spacing:4) { TextField("Type a message...", text:$viewModel.sendText).textFieldStyle(.roundedBorder).font(.caption).onSubmit { viewModel.sendMessage() }; Button(action:viewModel.sendMessage) { Image(systemName:"arrow.up.circle.fill").font(.title3) }.buttonStyle(.plain).disabled(viewModel.sendText.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty) }
    } }

    private var messageLogPanel: some View { VStack(alignment:.leading,spacing:0) {
        HStack { Text("Messages").font(.headline); Spacer(); Button("Clear") { viewModel.messages.removeAll() }.font(.caption).buttonStyle(.plain).foregroundColor(.secondary) }.padding(.horizontal).padding(.vertical,8)
        Divider()
        if viewModel.messages.isEmpty { VStack { Spacer(); Image(systemName:"text.bubble").font(.title).foregroundColor(.secondary.opacity(0.4)); Text("No messages yet").foregroundColor(.secondary).font(.caption); Spacer() }.frame(maxWidth:.infinity) }
        else { ScrollViewReader { proxy in ScrollView { LazyVStack(alignment:.leading,spacing:6) { ForEach(viewModel.messages) { msg in MessageRowView(message:msg).id(msg.id) } }.padding(8) }.onChange(of:viewModel.messages.count) { _ in if let last = viewModel.messages.last?.id { withAnimation { proxy.scrollTo(last, anchor:.bottom) } } } } }
    }.background(Color(nsColor:.controlBackgroundColor)) }
}

struct MessageRowView: View {
    let message: LoggedMessage
    var body: some View { HStack(alignment:.top,spacing:6) {
        Image(systemName:iconName).font(.caption2).foregroundColor(iconColor).frame(width:14)
        VStack(alignment:.leading,spacing:2) { HStack(spacing:4) { Text(message.sender).font(.caption).fontWeight(.medium); Text(message.timestamp,style:.time).font(.caption2).foregroundColor(.secondary) }; Text(message.text).font(.caption).foregroundColor(message.direction == .system ? .secondary : .primary).textSelection(.enabled) }
    }.padding(4).background(RoundedRectangle(cornerRadius:4).fill(message.direction == .system ? Color.clear : iconColor.opacity(0.06))) }
    private var iconName: String { switch message.direction { case .received: "arrow.down.left"; case .sent: "arrow.up.right"; case .system: "info.circle" } }
    private var iconColor: Color { switch message.direction { case .received: .blue; case .sent: .green; case .system: .secondary } }
}
