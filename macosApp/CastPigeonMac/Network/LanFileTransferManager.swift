import Foundation
import Network
import AppKit

final class LanFileTransferManager: ObservableObject {
    static let shared = LanFileTransferManager()

    enum TransferDirection {
        case sending
        case receiving
    }

    enum TransferPhase {
        case inProgress
        case success
        case failed
    }

    struct TransferStatus {
        let fileName: String
        let peerLabel: String
        let direction: TransferDirection
        let phase: TransferPhase
        let bytesTransferred: Int64
        let totalBytes: Int64?
        let detail: String?

        var progressFraction: Double? {
            guard let totalBytes, totalBytes > 0 else { return nil }
            return Double(bytesTransferred) / Double(totalBytes)
        }
    }

    private let defaultPort: UInt16 = 48602
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "CastPigeon.LanFileTransfer")

    @Published private(set) var serverPort: Int = 48602
    @Published private(set) var transferStatus: TransferStatus? = nil

    private init() {}

    func startServer() {
        guard listener == nil else { return }

        for offset in 0..<20 {
            let candidatePort = defaultPort + UInt16(offset)
            do {
                let nwPort = NWEndpoint.Port(rawValue: candidatePort)!
                let listener = try NWListener(using: .tcp, on: nwPort)
                self.listener = listener
                self.serverPort = Int(candidatePort)

                listener.newConnectionHandler = { [weak self] connection in
                    self?.handle(connection: connection)
                }
                listener.start(queue: queue)
                print("LAN file receiver started on port \(candidatePort)")
                return
            } catch {
                print("Failed to start LAN file receiver on port \(candidatePort): \(error)")
            }
        }

        print("Failed to start LAN file receiver")
    }

    func sendFile(fileURL: URL, to device: UdpDevice, completion: @escaping (Bool) -> Void) {
        guard let ip = device.ip, let port = device.filePort else {
            completion(false)
            return
        }
        let totalBytes = (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init)
        DispatchQueue.main.async {
            self.transferStatus = TransferStatus(
                fileName: fileURL.lastPathComponent,
                peerLabel: "\(ip):\(port)",
                direction: .sending,
                phase: .inProgress,
                bytesTransferred: 0,
                totalBytes: totalBytes,
                detail: nil
            )
        }

        var components = URLComponents()
        components.scheme = "http"
        components.host = ip
        components.port = port
        components.path = "/upload"
        components.queryItems = [
            URLQueryItem(name: "name", value: fileURL.lastPathComponent)
        ]
        guard let url = components.url else {
            completion(false)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(fileURL.lastPathComponent.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed), forHTTPHeaderField: "X-CastPigeon-Filename")

        let progress = Progress(totalUnitCount: totalBytes ?? 0)
        let observation = progress.observe(\.completedUnitCount) { [weak self] progress, _ in
            DispatchQueue.main.async {
                self?.transferStatus = TransferStatus(
                    fileName: fileURL.lastPathComponent,
                    peerLabel: "\(ip):\(port)",
                    direction: .sending,
                    phase: .inProgress,
                    bytesTransferred: progress.completedUnitCount,
                    totalBytes: totalBytes,
                    detail: nil
                )
            }
        }

        let task = URLSession.shared.uploadTask(with: request, fromFile: fileURL) { [weak self] _, response, error in
            observation.invalidate()
            if let error = error {
                print("LAN file send failed: \(error)")
                DispatchQueue.main.async {
                    self?.transferStatus = TransferStatus(
                        fileName: fileURL.lastPathComponent,
                        peerLabel: "\(ip):\(port)",
                        direction: .sending,
                        phase: .failed,
                        bytesTransferred: 0,
                        totalBytes: totalBytes,
                        detail: error.localizedDescription
                    )
                    completion(false)
                }
                return
            }
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            print("LAN file send finished: code=\(code)")
            DispatchQueue.main.async {
                self?.transferStatus = TransferStatus(
                    fileName: fileURL.lastPathComponent,
                    peerLabel: "\(ip):\(port)",
                    direction: .sending,
                    phase: (200...299).contains(code) ? .success : .failed,
                    bytesTransferred: totalBytes ?? 0,
                    totalBytes: totalBytes,
                    detail: "HTTP \(code)"
                )
                completion((200...299).contains(code))
            }
        }
        task.progress.totalUnitCount = totalBytes ?? 0
        task.resume()
    }

    private func handle(connection: NWConnection) {
        connection.start(queue: queue)
        var buffer = Data()

        func receiveNext() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let data = data {
                    buffer.append(data)
                }
                if self.hasCompleteRequest(buffer) || isComplete || error != nil {
                    self.processRequest(buffer, connection: connection)
                    return
                }
                receiveNext()
            }
        }

        receiveNext()
    }

    private func processRequest(_ data: Data, connection: NWConnection) {
        guard let separatorRange = data.range(of: Data("\r\n\r\n".utf8)) else {
            writeResponse(connection: connection, code: 400, body: "Bad Request")
            return
        }

        let headerData = data[..<separatorRange.lowerBound]
        let bodyStart = separatorRange.upperBound
        let bodyData = data[bodyStart...]
        guard let headerText = String(data: headerData, encoding: .utf8) else {
            writeResponse(connection: connection, code: 400, body: "Bad Request")
            return
        }

        let lines = headerText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first, requestLine.hasPrefix("POST /upload") else {
            writeResponse(connection: connection, code: 404, body: "Not Found")
            return
        }

        var fileName = "CastPigeon-\(Int(Date().timeIntervalSince1970))"
        for line in lines.dropFirst() {
            let parts = line.split(separator: ":", maxSplits: 1).map { String($0).trimmingCharacters(in: .whitespaces) }
            if parts.count == 2 && parts[0].lowercased() == "x-castpigeon-filename" {
                fileName = parts[1].removingPercentEncoding ?? parts[1]
            }
        }

        if let nameQuery = requestLine.components(separatedBy: "name=").dropFirst().first?.components(separatedBy: " ").first {
            fileName = nameQuery.removingPercentEncoding ?? fileName
        }

        let targetURL = uniqueDownloadURL(fileName: sanitize(fileName))
        do {
            try Data(bodyData).write(to: targetURL)
            print("LAN file received: \(targetURL.path)")
            DispatchQueue.main.async {
                self.transferStatus = TransferStatus(
                    fileName: targetURL.lastPathComponent,
                    peerLabel: connection.endpoint.debugDescription,
                    direction: .receiving,
                    phase: .success,
                    bytesTransferred: Int64(bodyData.count),
                    totalBytes: Int64(bodyData.count),
                    detail: targetURL.path
                )
            }
            writeResponse(connection: connection, code: 200, body: "OK")
        } catch {
            print("Failed to save LAN file: \(error)")
            DispatchQueue.main.async {
                self.transferStatus = TransferStatus(
                    fileName: fileName,
                    peerLabel: connection.endpoint.debugDescription,
                    direction: .receiving,
                    phase: .failed,
                    bytesTransferred: 0,
                    totalBytes: Int64(bodyData.count),
                    detail: error.localizedDescription
                )
            }
            writeResponse(connection: connection, code: 500, body: "Failed")
        }
    }

    private func hasCompleteRequest(_ data: Data) -> Bool {
        guard let separatorRange = data.range(of: Data("\r\n\r\n".utf8)) else {
            return false
        }
        let headerData = data[..<separatorRange.lowerBound]
        guard let headerText = String(data: headerData, encoding: .utf8) else {
            return true
        }
        let contentLength = headerText
            .components(separatedBy: "\r\n")
            .dropFirst()
            .compactMap { line -> Int? in
                let parts = line.split(separator: ":", maxSplits: 1).map { String($0).trimmingCharacters(in: .whitespaces) }
                guard parts.count == 2, parts[0].lowercased() == "content-length" else { return nil }
                return Int(parts[1])
            }
            .first ?? 0
        return data.count - separatorRange.upperBound >= contentLength
    }

    private func writeResponse(connection: NWConnection, code: Int, body: String) {
        let status = code == 200 ? "OK" : "Error"
        let response = "HTTP/1.1 \(code) \(status)\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func uniqueDownloadURL(fileName: String) -> URL {
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        var candidate = downloads.appendingPathComponent(fileName)
        let base = candidate.deletingPathExtension().lastPathComponent
        let ext = candidate.pathExtension
        var index = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let suffix = ext.isEmpty ? "" : ".\(ext)"
            candidate = downloads.appendingPathComponent("\(base) (\(index))\(suffix)")
            index += 1
        }
        return candidate
    }

    private func sanitize(_ fileName: String) -> String {
        let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|")
        let parts = fileName.components(separatedBy: invalid).filter { !$0.isEmpty }
        return parts.joined(separator: "_").isEmpty ? "CastPigeon-\(Int(Date().timeIntervalSince1970))" : parts.joined(separator: "_")
    }
}
