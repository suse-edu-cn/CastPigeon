import Foundation
import Network
import UserNotifications

struct DiscoveredDevice: Identifiable {
    let id: String; let name: String; let displayInfo: String; let endpoint: NWEndpoint
}
enum ConnectionState: String, CaseIterable {
    case idle="Ready", browsing="Browsing...", broadcasting="Broadcasting...", connecting="Connecting...", connected="Connected", paired="Paired"
}
struct LoggedMessage: Identifiable {
    let id=UUID(); let timestamp:Date; let direction:Direction; let sender:String; let text:String
    enum Direction { case received, sent, system }
}
struct PeerMessage: Codable {
    let type:String; var deviceName:String?; var deviceId:String?; var platform:String?
    var message:String?; var id:String?; var appName:String?; var title:String?; var content:String?; var timestamp:Int64?; var reason:String?
}
protocol NetworkManagerDelegate: AnyObject {
    func networkManagerDidUpdateState(_ state:ConnectionState)
    func networkManagerDidDiscoverDevice(_ device:DiscoveredDevice)
    func networkManagerDidRemoveDevice(_ device:DiscoveredDevice)
    func networkManagerDidReceiveMessage(_ message:LoggedMessage)
    func networkManagerDidReceivePairRequest(from deviceName:String)
}

final class NetworkManager: NSObject {
    static let shared = NetworkManager()
    weak var delegate: NetworkManagerDelegate?
    private(set) var state:ConnectionState = .idle { didSet { DispatchQueue.main.async { [weak self] in self?.delegate?.networkManagerDidUpdateState(self?.state ?? .idle) } } }
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var activeConnection: NWConnection?
    private var discoveredEndpoints: [String: NWEndpoint] = [:]
    private var pendingPairDeviceName: String?
    private let serviceType = "_castpigeon._tcp"
    private let serviceName: String
    private let port: UInt16 = 9876

    override init() {
        serviceName = Host.current().localizedName ?? "CastPigeon Mac"
        super.init()
    }

    func startBrowsingAndBroadcasting() { startBrowsing(); startBroadcasting() }
    func stopAll() { stopBrowsing(); stopBroadcasting(); disconnect() }

    private func startBrowsing() {
        discoveredEndpoints.removeAll()
        let params = NWParameters(); params.includePeerToPeer = true
        browser = NWBrowser(for: .bonjour(type: serviceType, domain: "local."), using: params)
        browser?.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            var current: [String: NWEndpoint] = [:]
            for result in results {
                switch result.endpoint {
                case .service(let name, _, _, _):
                    guard name != self.serviceName else { continue }
                    current[name] = result.endpoint
                    if self.discoveredEndpoints[name] == nil {
                        self.discoveredEndpoints[name] = result.endpoint
                        let dev = DiscoveredDevice(id: name, name: name, displayInfo: "Bonjour: \(name).\(self.serviceType).local.", endpoint: result.endpoint)
                        self.delegate?.networkManagerDidDiscoverDevice(dev)
                        print("[CastPigeon] Discovered: \(name)")
                    }
                default: break
                }
            }
            let prevNames = Array(self.discoveredEndpoints.keys)
            let currNames = Array(current.keys)
            let removed = Set(prevNames).subtracting(currNames)
            for name in removed {
                let fallback = NWEndpoint.service(name: name, type: self.serviceType, domain: "local.", interface: nil)
                let dev = DiscoveredDevice(id: name, name: name, displayInfo: "", endpoint: self.discoveredEndpoints[name] ?? fallback)
                self.delegate?.networkManagerDidRemoveDevice(dev)
            }
            self.discoveredEndpoints = current
        }
        browser?.stateUpdateHandler = { s in if case .failed(let e) = s { print("[CastPigeon] Browser failed: \(e)") } }
        browser?.start(queue: .main)
    }
    private func stopBrowsing() { browser?.cancel(); browser = nil }

    private func startBroadcasting() {
        do {
            let params = NWParameters.tcp; params.includePeerToPeer = true
            listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
            listener?.service = NWListener.Service(name: serviceName, type: serviceType)
            listener?.serviceRegistrationUpdateHandler = { change in
                if case .add(let ep) = change, case .service(let n, _, _, _) = ep { print("[CastPigeon] Broadcasting as '\(n)'") }
            }
            listener?.stateUpdateHandler = { [weak self] s in
                if case .ready = s { self?.state = .broadcasting }
            }
            listener?.newConnectionHandler = { [weak self] conn in
                guard self?.activeConnection?.state != .ready else { conn.cancel(); return }
                self?.acceptIncoming(conn)
            }
            listener?.start(queue: .main)
        } catch { print("[CastPigeon] Listener: \(error)") }
    }
    private func stopBroadcasting() { listener?.cancel(); listener = nil }

    private func acceptIncoming(_ conn: NWConnection) {
        disconnect()
        activeConnection = conn
        conn.stateUpdateHandler = { [weak self] s in
            switch s {
            case .ready:
                print("[CastPigeon] Accepted connection (server)")
                self?.state = .connected
                self?.receive()
            case .failed, .cancelled:
                print("[CastPigeon] Server connection ended")
                self?.disconnect()
            default: break
            }
        }
        conn.start(queue: .main)
    }

    func connectToDevice(_ device: DiscoveredDevice) {
        guard state != .connected, state != .paired else { return }
        disconnect(); state = .connecting
        let conn = NWConnection(to: device.endpoint, using: .tcp)
        activeConnection = conn
        conn.stateUpdateHandler = { [weak self] s in
            switch s {
            case .ready:
                print("[CastPigeon] Connected to \(device.name)")
                self?.state = .connected
                self?.receive()
                self?.send(json:["type":"pair_request","deviceName":self?.serviceName ?? "Mac","deviceId":"macOS","platform":"macOS"])
            case .waiting(let e): print("[CastPigeon] Waiting: \(e)")
            case .failed(let e): print("[CastPigeon] Connect failed: \(e)"); self?.disconnect()
            default: break
            }
        }
        conn.start(queue: .main)
    }

    func connectToIP(_ ip: String, port: UInt16 = 9876) {
        guard state != .connected, state != .paired else { return }
        disconnect(); state = .connecting
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        activeConnection = conn
        conn.stateUpdateHandler = { [weak self] s in
            switch s {
            case .ready:
                print("[CastPigeon] Connected to \(ip):\(port)")
                self?.state = .connected
                self?.receive()
                self?.send(json:["type":"pair_request","deviceName":self?.serviceName ?? "Mac","deviceId":"macOS","platform":"macOS"])
            case .failed(let e): print("[CastPigeon] Connect failed: \(e)"); self?.disconnect()
            default: break
            }
        }
        conn.start(queue: .main)
    }

    // MARK: - Pair Accept / Reject

    func acceptPairRequest() {
        defer { pendingPairDeviceName = nil }
        send(json:["type":"pair_accept","deviceName":serviceName,"platform":"macOS"])
        state = .paired
        delegate?.networkManagerDidReceiveMessage(
            LoggedMessage(timestamp:Date(), direction:.system, sender:"System", text:"Pairing accepted"))
    }

    func rejectPairRequest() {
        defer { pendingPairDeviceName = nil }
        send(json:["type":"pair_reject","reason":"User declined"])
        disconnect()
    }

    func disconnect() { activeConnection?.cancel(); activeConnection = nil; if state != .broadcasting { state = .idle } }

    func sendTestMessage(_ text: String) {
        send(json:["type":"test_message","message":text])
        delegate?.networkManagerDidReceiveMessage(LoggedMessage(timestamp:Date(), direction:.sent, sender:"This Mac", text:text))
    }
    func sendSimulatedNotification(appName: String, title: String, content: String) {
        send(json:["type":"notification","id":UUID().uuidString,"appName":appName,"title":title,"content":content,"timestamp":Int64(Date().timeIntervalSince1970*1000)])
        delegate?.networkManagerDidReceiveMessage(LoggedMessage(timestamp:Date(), direction:.sent, sender:appName, text:"\(title): \(content)"))
    }
    func sendPing() { send(json:["type":"ping"]) }

    private func send(json dict: [String: Any]) {
        guard let conn = activeConnection, conn.state == .ready else { print("[CastPigeon] Cannot send: no active connection (state=\(state.rawValue))"); return }
        guard let data = try? JSONSerialization.data(withJSONObject: dict), var text = String(data: data, encoding: .utf8) else { return }
        text += "\n"
        conn.send(content: text.data(using: .utf8)!, completion: .contentProcessed { e in if let e { print("[CastPigeon] Send error: \(e)") } })
    }

    private func receive() {
        activeConnection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self, self.activeConnection != nil else { return }
            if let error { print("[CastPigeon] Recv error: \(error)"); return }
            if let data, !data.isEmpty { self.process(data) }
            if !isComplete { self.receive() }
        }
    }

    private func process(_ data: Data) {
        guard let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
        for line in text.components(separatedBy: "\n") where !line.isEmpty {
            guard let jd = line.data(using: .utf8), let msg = try? JSONDecoder().decode(PeerMessage.self, from: jd) else { continue }
            handle(msg)
        }
    }

    private func handle(_ msg: PeerMessage) {
        switch msg.type {
        case "pair_request":
            let dn = msg.deviceName ?? "Unknown"
            delegate?.networkManagerDidReceiveMessage(
                LoggedMessage(timestamp:Date(), direction:.received, sender:dn, text:"Pair request from \(dn)"))
            pendingPairDeviceName = dn
            delegate?.networkManagerDidReceivePairRequest(from: dn)

        case "pair_accept":
            let dn = msg.deviceName ?? "Unknown"
            delegate?.networkManagerDidReceiveMessage(
                LoggedMessage(timestamp:Date(), direction:.system, sender:"System", text:"Paired with \(dn)"))
            state = .paired

        case "pair_reject":
            let reason = msg.reason ?? ""
            delegate?.networkManagerDidReceiveMessage(
                LoggedMessage(timestamp:Date(), direction:.system, sender:"System", text:"Pairing rejected: \(reason)"))
            disconnect()

        case "notification":
            let an=msg.appName ?? ""; let t=msg.title ?? ""; let c=msg.content ?? ""
            delegate?.networkManagerDidReceiveMessage(LoggedMessage(timestamp:Date(), direction:.received, sender:an, text:"\(t): \(c)"))
            showNotification(appName:an, title:t, body:c)

        case "test_message":
            let txt=msg.message ?? ""
            delegate?.networkManagerDidReceiveMessage(LoggedMessage(timestamp:Date(), direction:.received, sender:msg.deviceName ?? "Android", text:txt))
            showNotification(appName:"CastPigeon", title:"Message", body:txt)

        case "ping": send(json:["type":"pong"])
        case "pong": delegate?.networkManagerDidReceiveMessage(LoggedMessage(timestamp:Date(), direction:.system, sender:"System", text:"Pong received"))
        default: print("[CastPigeon] Unknown: \(msg.type)")
        }
    }

    private func showNotification(appName:String, title:String, body:String) {

        DispatchQueue.main.async {
            let c=UNMutableNotificationContent(); c.title=appName; c.subtitle=title; c.body=body; c.sound = .default
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier:UUID().uuidString, content:c, trigger:nil))
        }
    }
}
