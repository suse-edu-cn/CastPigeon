import Foundation
import Darwin

class SwiftUdpDiscovery {
    static let shared = SwiftUdpDiscovery()
    
    private let socketQueue = DispatchQueue(label: "CastPigeon.UdpDiscovery.Socket", qos: .userInitiated)
    private let socketLock = NSLock()
    private var socketFD: Int32 = -1
    private var isClosingSocket: Bool = false
    private var socketStartPending: Bool = false
    private var listenSource: DispatchSourceRead?
    private var broadcastTimer: DispatchSourceTimer?
    private var restartTimer: DispatchSourceTimer?
    private let port: UInt16 = 48500
    private let bindingPinTTL: TimeInterval = 120.0
    
    var onDeviceDiscovered: (([UdpDevice]) -> Void)?
    var onPairingSuccess: ((UdpDevice) -> Void)?
    var onPinDisplayRequested: ((String, UdpDevice) -> Void)?
    var onPinInputRequested: ((UdpDevice) -> Void)?
    var onDebugLog: ((String) -> Void)?
    
    private var devices: Set<UdpDevice> = []
    private var myPairingHash: String? = nil
    private var myRole: String? = nil
    private var myName: String? = nil
    private var isPairingOpen: Bool = false
    private var trustedPeerHashes: Set<String> = []
    private var currentBroadcastMessage: String?
    
    private var currentExpectedPin: String? = nil
    private var currentPairingTargetHash: String? = nil
    private var currentPairingPinCreatedAt: Date? = nil
    private var pendingBindingTarget: UdpDevice?
    private var pendingBindingTargetIp: String?
    private let ignoredBroadcastInterfacePrefixes = ["lo", "utun", "awdl", "llw", "bridge", "feth", "gif", "stf"]

    private func sortedDevices() -> [UdpDevice] {
        devices.sorted {
            let lhsName = $0.deviceName.localizedCaseInsensitiveCompare($1.deviceName)
            if lhsName != .orderedSame {
                return lhsName == .orderedAscending
            }
            return $0.hash_ < $1.hash_
        }
    }

    private func log(_ message: String) {
        DispatchQueue.main.async {
            self.onDebugLog?(message)
        }
    }
    
    private func setupSocket() {
        socketLock.lock()
        defer { socketLock.unlock() }
        guard socketFD == -1 else { return }
        guard !isClosingSocket else {
            socketStartPending = true
            return
        }
        
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else {
            log("UDP socket 创建失败")
            return
        }
        
        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &reuse, socklen_t(MemoryLayout<Int32>.size))
        
        var broadcast: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &broadcast, socklen_t(MemoryLayout<Int32>.size))
        
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian
        
        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        
        guard bindResult >= 0 else {
            log("UDP socket 绑定 48500 端口失败")
            close(fd)
            return
        }

        socketFD = fd
        log("UDP 监听已启动: 0.0.0.0:\(port)")
        
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: socketQueue)
        source.setEventHandler { [weak self] in
            self?.readData(from: fd)
        }
        source.setCancelHandler { [weak self] in
            close(fd)
            self?.markSocketClosed(fd)
        }
        listenSource = source
        source.resume()
    }
    
    func startListening(role: String, deviceName: String, hash: String, pairingMode: Bool, trustedHashes: Set<String>) {
        if myPairingHash != hash || myRole != role || myName != deviceName || !pairingMode {
            clearPairingPinState()
        }
        self.myPairingHash = hash
        self.myRole = role
        self.myName = deviceName
        self.isPairingOpen = pairingMode
        self.trustedPeerHashes = Set(trustedHashes.map { $0.uppercased() })
        log("UDP 发现服务启动: role=\(role), pairing=\(pairingMode), hash=\(hash), trusted=\(trustedPeerHashes.count)")
        
        setupSocket()
        
        // 启动看门狗定时器：解决 macOS 首次申请“本地网络”权限时，BSD Socket 被系统底层永久挂起（即使授权后也不恢复）的经典 Bug
        restartTimer?.cancel()
        restartTimer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .background))
        restartTimer?.schedule(deadline: .now() + 3.0, repeating: 3.0)
        restartTimer?.setEventHandler { [weak self] in
            guard let self = self else { return }
            if self.devices.isEmpty && self.currentExpectedPin == nil {
                self.log("UDP 监听看门狗重启 socket")
                self.closeListeningSocket()
                self.setupSocket()
            }
        }
        restartTimer?.resume()
    }
    
    private func readData(from fd: Int32) {
        var buffer = [UInt8](repeating: 0, count: 65535)
        var senderAddr = sockaddr_in()
        var senderAddrLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        
        let bytesRead = withUnsafeMutablePointer(to: &senderAddr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { addrPtr in
                recvfrom(fd, &buffer, buffer.count, 0, addrPtr, &senderAddrLen)
            }
        }
        
        guard bytesRead > 0 else { return }
        let data = Data(buffer[0..<bytesRead])
        guard let msg = String(data: data, encoding: .utf8) else { return }
        
        // Extract IP string
        let ipString = String(cString: inet_ntoa(senderAddr.sin_addr))
        
        let parts = msg.components(separatedBy: "|")
        
        if parts.count >= 4 && parts[0] == "CP_PAIR" {
            if parts[3] == self.myPairingHash {
                return
            }
            if !isPairingOpen && !trustedPeerHashes.contains(parts[3].uppercased()) {
                return
            }
            let filePort = parts.count >= 5 ? Int(parts[4]) : nil
            let deviceType = parts.count >= 6 ? parts[5] : "Unknown"
            let newDevice = UdpDevice(deviceName: parts[2], role: parts[1], hash_: parts[3], ip: ipString, filePort: filePort.flatMap { $0 > 0 ? $0 : nil }, deviceType: deviceType)
            DispatchQueue.main.async {
                let existed = self.devices.contains { existing in
                    existing.hash_ == newDevice.hash_ &&
                    existing.ip == newDevice.ip &&
                    existing.filePort == newDevice.filePort &&
                    existing.deviceType == newDevice.deviceType
                }
                self.devices = Set(self.devices.filter { $0.hash_ != newDevice.hash_ })
                self.devices.insert(newDevice)
                if !existed {
                    self.onDebugLog?("UDP 发现设备: \(newDevice.deviceName) / \(newDevice.hash_) / \(newDevice.ip ?? "unknown") / \(newDevice.deviceType)")
                }
                self.onDeviceDiscovered?(self.sortedDevices())
            }
            
        } else if parts.count == 5 && parts[0] == "CP_BIND_REQUEST" {
            guard isPairingOpen else { return }
            let targetHash = parts[1]
            if targetHash == self.myPairingHash {
                let reqRole = parts[2]
                let reqName = parts[3]
                let reqHash = parts[4]
                if reqHash == self.myPairingHash { return }
                let requestingDevice = UdpDevice(deviceName: reqName, role: reqRole, hash_: reqHash, ip: ipString)
                let (pin, reused) = self.pinForBindRequest(requesterHash: reqHash)
                log("UDP 收到绑定请求: \(reqName) / \(reqHash) / \(ipString)，复用配对码=\(reused)")
                
                DispatchQueue.main.async {
                    self.onPinDisplayRequested?(pin, requestingDevice)
                }
            }
            
        } else if parts.count == 6 && parts[0] == "CP_BIND_VERIFY" {
            guard isPairingOpen else { return }
            let targetHash = parts[1]
            if targetHash == self.myPairingHash {
                let reqRole = parts[2]
                let reqName = parts[3]
                let reqHash = parts[4]
                if reqHash == self.myPairingHash { return }
                let receivedPin = parts[5]
                let requestingDevice = UdpDevice(deviceName: reqName, role: reqRole, hash_: reqHash, ip: ipString)
                
                if self.isExpectedPairingPin(requesterHash: reqHash, pin: receivedPin) {
                    log("UDP 配对码验证成功: \(reqName) / \(reqHash)")
                    self.clearPairingPinState()
                    
                    self.sendUdpMessage("CP_BIND_SUCCESS|\(reqHash)|\(self.myPairingHash ?? "")", targetIp: ipString)
                    
                    DispatchQueue.main.async {
                        self.onPairingSuccess?(requestingDevice)
                    }
                } else {
                    log("UDP 配对码验证失败: \(reqName) / \(reqHash)，当前目标=\(self.currentPairingTargetHash ?? "none")，存在有效码=\(self.currentExpectedPin != nil)")
                }
            }
            
        } else if parts.count == 3 && parts[0] == "CP_BIND_SUCCESS" {
            let targetHash = parts[1]
            let senderHash = parts[2]
            if targetHash == self.myPairingHash {
                if var updatedDevice = self.pendingBindingTarget, updatedDevice.hash_ == senderHash {
                    updatedDevice.ip = ipString
                    self.pendingBindingTarget = nil
                    self.pendingBindingTargetIp = nil
                    self.log("UDP 收到绑定成功: \(updatedDevice.deviceName) / \(updatedDevice.hash_)")
                    DispatchQueue.main.async {
                        self.onPairingSuccess?(updatedDevice)
                    }
                } else if let device = self.devices.first(where: { $0.hash_ == senderHash }) {
                    var updatedDevice = device
                    updatedDevice.ip = ipString
                    self.log("UDP 收到绑定成功: \(updatedDevice.deviceName) / \(updatedDevice.hash_)")
                    DispatchQueue.main.async {
                        self.onPairingSuccess?(updatedDevice)
                    }
                }
            }
        }
    }
    
    func startBroadcasting(
        role: String,
        deviceName: String,
        hash: String,
        filePort: Int? = nil,
        deviceType: String = "Mac",
        pairingMode: Bool = true,
        trustedHashes: Set<String> = []
    ) {
        startListening(role: role, deviceName: deviceName, hash: hash, pairingMode: pairingMode, trustedHashes: trustedHashes)
        
        let msg = "CP_PAIR|\(role)|\(deviceName)|\(hash)|\(filePort ?? 0)|\(deviceType)"
        currentBroadcastMessage = msg
        broadcastTimer?.cancel()
        log("UDP 广播已启动: role=\(role), pairing=\(pairingMode), device=\(deviceName), hash=\(hash)")
        
        broadcastTimer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        broadcastTimer?.schedule(deadline: .now(), repeating: 1.0)
        broadcastTimer?.setEventHandler { [weak self] in
            guard let self, let message = self.currentBroadcastMessage else { return }
            self.sendUdpMessage(message)
        }
        broadcastTimer?.resume()
    }
    
    func requestBinding(targetHash: String, targetDeviceName: String, targetRole: String, targetIp: String? = nil) {
        guard let myRole = myRole, let myName = myName, let myHash = myPairingHash else { return }
        guard targetHash != myHash else { return }
        pendingBindingTarget = UdpDevice(deviceName: targetDeviceName, role: targetRole, hash_: targetHash, ip: targetIp)
        pendingBindingTargetIp = targetIp
        log("UDP 发送绑定请求: \(targetDeviceName) / \(targetHash) / \(targetIp ?? "broadcast")")
        
        sendUdpMessage("CP_BIND_REQUEST|\(targetHash)|\(myRole)|\(myName)|\(myHash)", targetIp: targetIp)
        
        DispatchQueue.main.async {
            let targetDevice = UdpDevice(deviceName: targetDeviceName, role: targetRole, hash_: targetHash, ip: targetIp)
            self.onPinInputRequested?(targetDevice)
        }
    }
    
    func verifyBinding(targetHash: String, pin: String, targetIp: String? = nil) {
        guard let myRole = myRole, let myName = myName, let myHash = myPairingHash else { return }
        log("UDP 发送配对码验证: target=\(targetHash), ip=\(targetIp ?? pendingBindingTargetIp ?? "broadcast")")
        sendUdpMessage("CP_BIND_VERIFY|\(targetHash)|\(myRole)|\(myName)|\(myHash)|\(pin)", targetIp: targetIp ?? pendingBindingTargetIp)
    }
    
    private func sendUdpMessage(_ msg: String, targetIp: String? = nil) {
        guard let data = msg.data(using: .utf8) else { return }
        
        let tempSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard tempSocket >= 0 else { return }
        
        var broadcast: Int32 = 1
        setsockopt(tempSocket, SOL_SOCKET, SO_BROADCAST, &broadcast, socklen_t(MemoryLayout<Int32>.size))
        
        let targets = udpTargets(for: targetIp)
        
        // 发送3次，不依赖 RunLoop；广播时逐个真实网卡的广播地址发送，避免 TUN/VPN 接管 255.255.255.255。
        DispatchQueue.global(qos: .userInitiated).async {
            for _ in 0..<3 {
                for target in targets {
                    var addr = sockaddr_in()
                    addr.sin_family = sa_family_t(AF_INET)
                    addr.sin_port = self.port.bigEndian
                    addr.sin_addr.s_addr = inet_addr(target)

                    data.withUnsafeBytes { rawBuffer in
                        if let baseAddress = rawBuffer.baseAddress {
                            _ = withUnsafePointer(to: &addr) {
                                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { addrPtr in
                                    sendto(tempSocket, baseAddress, data.count, 0, addrPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
                                }
                            }
                        }
                    }
                }
                Thread.sleep(forTimeInterval: 0.2)
            }
            close(tempSocket)
        }
    }

    private func udpTargets(for targetIp: String?) -> [String] {
        if let targetIp = targetIp, !targetIp.isEmpty {
            return Array(Set([targetIp] + activeIPv4BroadcastAddresses()))
        }

        let interfaceBroadcasts = activeIPv4BroadcastAddresses()
        return interfaceBroadcasts.isEmpty ? ["255.255.255.255"] : interfaceBroadcasts
    }

    private func activeIPv4BroadcastAddresses() -> [String] {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else {
            return []
        }
        defer { freeifaddrs(interfaces) }

        var broadcasts: [String] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }

            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & IFF_UP) != 0,
                  (flags & IFF_RUNNING) != 0,
                  (flags & IFF_BROADCAST) != 0,
                  let address = current.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  let broadcastAddress = current.pointee.ifa_dstaddr else {
                continue
            }

            let interfaceName = String(cString: current.pointee.ifa_name)
            if ignoredBroadcastInterfacePrefixes.contains(where: { interfaceName.hasPrefix($0) }) {
                continue
            }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                broadcastAddress,
                socklen_t(broadcastAddress.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            if result == 0 {
                let ip = String(cString: host)
                if !broadcasts.contains(ip) {
                    broadcasts.append(ip)
                }
            }
        }
        return broadcasts
    }
    
    func stop() {
        closeListeningSocket()
        broadcastTimer?.cancel()
        broadcastTimer = nil
        restartTimer?.cancel()
        restartTimer = nil
        
        myPairingHash = nil
        myRole = nil
        myName = nil
        isPairingOpen = false
        trustedPeerHashes = []
        currentBroadcastMessage = nil
        clearPairingPinState()
        pendingBindingTarget = nil
        pendingBindingTargetIp = nil
        devices.removeAll()
        DispatchQueue.main.async {
            self.onDeviceDiscovered?([])
        }
        log("UDP 发现服务已停止")
    }

    private func pinForBindRequest(requesterHash: String) -> (String, Bool) {
        let now = Date()
        if let pin = currentExpectedPin,
           currentPairingTargetHash == requesterHash,
           !isPairingPinExpired(now: now) {
            return (pin, true)
        }

        let pin = String(format: "%04d", Int.random(in: 1000...9999))
        currentExpectedPin = pin
        currentPairingTargetHash = requesterHash
        currentPairingPinCreatedAt = now
        return (pin, false)
    }

    private func isExpectedPairingPin(requesterHash: String, pin: String) -> Bool {
        let now = Date()
        let matches = currentExpectedPin == pin &&
            currentPairingTargetHash == requesterHash &&
            !isPairingPinExpired(now: now)
        if !matches && currentPairingTargetHash == requesterHash && isPairingPinExpired(now: now) {
            clearPairingPinState()
        }
        return matches
    }

    private func isPairingPinExpired(now: Date) -> Bool {
        guard let createdAt = currentPairingPinCreatedAt else { return true }
        return now.timeIntervalSince(createdAt) > bindingPinTTL
    }

    private func clearPairingPinState() {
        currentExpectedPin = nil
        currentPairingTargetHash = nil
        currentPairingPinCreatedAt = nil
    }

    private func closeListeningSocket() {
        socketLock.lock()
        defer { socketLock.unlock() }
        guard let source = listenSource else {
            socketFD = -1
            isClosingSocket = false
            return
        }
        let fd = socketFD
        listenSource = nil
        socketFD = -1
        isClosingSocket = true
        if fd >= 0 {
            shutdown(fd, SHUT_RDWR)
        }
        source.cancel()
    }

    private func markSocketClosed(_ fd: Int32) {
        socketLock.lock()
        if socketFD == fd {
            socketFD = -1
        }
        isClosingSocket = false
        let shouldRestart = socketStartPending
        socketStartPending = false
        socketLock.unlock()
        if shouldRestart {
            socketQueue.async { [weak self] in
                self?.setupSocket()
            }
        }
    }
}
