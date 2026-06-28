import Foundation
import SQLite3

enum DeviceRole: String, CaseIterable {
    case sender = "作为发送端"
    case receiver = "作为接收端"
}

enum WorkMode {
    case idle
    case pairing
    case working
}

struct UdpDevice: Hashable {
    let deviceName: String
    let role: String
    let hash_: String
    var ip: String? = nil
    var filePort: Int? = nil
    var deviceType: String = "Unknown"
    var prefixLength: Int? = nil
    var gateway: String? = nil
    var networkId: String? = nil
    var lanReachable: Bool = false
    var lastSeen: Int64 = 0
}

struct NotificationMessage: Codable {
    let id: String
    let deviceHash: String
    let appName: String
    let title: String
    let content: String
    let timestamp: Int64
    let iconBase64: String?

    init(
        id: String,
        deviceHash: String = "",
        appName: String,
        title: String,
        content: String,
        timestamp: Int64,
        iconBase64: String? = nil
    ) {
        self.id = id
        self.deviceHash = deviceHash
        self.appName = appName
        self.title = title
        self.content = content
        self.timestamp = timestamp
        self.iconBase64 = iconBase64
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        deviceHash = try container.decodeIfPresent(String.self, forKey: .deviceHash) ?? ""
        appName = try container.decodeIfPresent(String.self, forKey: .appName) ?? "Unknown"
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        content = try container.decodeIfPresent(String.self, forKey: .content) ?? ""
        timestamp = try container.decodeIfPresent(Int64.self, forKey: .timestamp) ?? Int64(Date().timeIntervalSince1970 * 1000)
        iconBase64 = try container.decodeIfPresent(String.self, forKey: .iconBase64)
    }

    func withDeviceHash(_ hash: String) -> NotificationMessage {
        NotificationMessage(
            id: id,
            deviceHash: hash,
            appName: appName,
            title: title,
            content: content,
            timestamp: timestamp,
            iconBase64: iconBase64
        )
    }
}

struct ClipboardHistoryItem {
    let id: Int64
    let content: String
    let direction: String
    let timestamp: Int64
}

class DatabaseManager {
    static let shared = DatabaseManager()
    
    private var db: OpaquePointer?
    
    private let dbPath: String
    private let iconsDir: String
    
    private init() {
        let fileManager = FileManager.default
        let appSupportDir = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support")
        let appDir = appSupportDir.appendingPathComponent("CastPigeon")
        
        iconsDir = appDir.appendingPathComponent("Icons").path
        dbPath = appDir.appendingPathComponent("messages.sqlite").path
        
        // Ensure directories exist
        try? fileManager.createDirectory(atPath: appDir.path, withIntermediateDirectories: true, attributes: nil)
        try? fileManager.createDirectory(atPath: iconsDir, withIntermediateDirectories: true, attributes: nil)
        
        openDatabase()
        createTable()
    }
    
    private func openDatabase() {
        if sqlite3_open(dbPath, &db) != SQLITE_OK {
            print("Error opening database at \(dbPath)")
        }
    }
    
    private func createTable() {
        let createMessagesTableString = """
        CREATE TABLE IF NOT EXISTS messages(
            id TEXT PRIMARY KEY,
            deviceHash TEXT,
            appName TEXT,
            title TEXT,
            content TEXT,
            timestamp INTEGER
        );
        """
        
        var createTableStatement: OpaquePointer?
        if sqlite3_prepare_v2(db, createMessagesTableString, -1, &createTableStatement, nil) == SQLITE_OK {
            if sqlite3_step(createTableStatement) == SQLITE_DONE {
                print("Messages table created.")
            } else {
                print("Messages table could not be created.")
            }
        } else {
            print("CREATE TABLE statement could not be prepared.")
        }
        sqlite3_finalize(createTableStatement)

        let createClipboardTableString = """
        CREATE TABLE IF NOT EXISTS clipboard_history(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            content TEXT NOT NULL,
            direction TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        );
        """

        var createClipboardTableStatement: OpaquePointer?
        if sqlite3_prepare_v2(db, createClipboardTableString, -1, &createClipboardTableStatement, nil) == SQLITE_OK {
            if sqlite3_step(createClipboardTableStatement) == SQLITE_DONE {
                print("Clipboard history table created.")
            } else {
                print("Clipboard history table could not be created.")
            }
        } else {
            print("CREATE clipboard history table statement could not be prepared.")
        }
        sqlite3_finalize(createClipboardTableStatement)
        sqlite3_exec(db, "CREATE INDEX IF NOT EXISTS idx_clipboard_history_timestamp ON clipboard_history(timestamp DESC);", nil, nil, nil)
    }
    
    @discardableResult
    func insertMessage(_ msg: NotificationMessage, deviceHash: String) -> Bool {
        // Handle Icon storage logic
        if let base64 = msg.iconBase64, !base64.isEmpty {
            let iconPath = (iconsDir as NSString).appendingPathComponent("\(msg.appName).png")
            if !FileManager.default.fileExists(atPath: iconPath) {
                if let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) {
                    try? data.write(to: URL(fileURLWithPath: iconPath))
                }
            }
        }
        
        let insertStatementString = "INSERT OR IGNORE INTO messages (id, deviceHash, appName, title, content, timestamp) VALUES (?, ?, ?, ?, ?, ?);"
        var insertStatement: OpaquePointer?
        var didInsert = false
        if sqlite3_prepare_v2(db, insertStatementString, -1, &insertStatement, nil) == SQLITE_OK {
            sqlite3_bind_text(insertStatement, 1, (msg.id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(insertStatement, 2, (deviceHash as NSString).utf8String, -1, nil)
            sqlite3_bind_text(insertStatement, 3, (msg.appName as NSString).utf8String, -1, nil)
            sqlite3_bind_text(insertStatement, 4, (msg.title as NSString).utf8String, -1, nil)
            sqlite3_bind_text(insertStatement, 5, (msg.content as NSString).utf8String, -1, nil)
            sqlite3_bind_int64(insertStatement, 6, msg.timestamp)
            
            if sqlite3_step(insertStatement) == SQLITE_DONE {
                didInsert = sqlite3_changes(db) > 0
                if didInsert {
                    print("Successfully inserted row.")
                } else {
                    print("Duplicate row ignored.")
                }
            } else {
                print("Could not insert row.")
            }
        } else {
            print("INSERT statement could not be prepared.")
        }
        sqlite3_finalize(insertStatement)
        return didInsert
    }
    
    func getMessages(for deviceHash: String? = nil) -> [NotificationMessage] {
        var queryStatementString = "SELECT id, deviceHash, appName, title, content, timestamp FROM messages"
        if deviceHash != nil {
            queryStatementString += " WHERE deviceHash = ?"
        }
        queryStatementString += " ORDER BY timestamp DESC"
        
        var queryStatement: OpaquePointer?
        var messages: [NotificationMessage] = []
        
        if sqlite3_prepare_v2(db, queryStatementString, -1, &queryStatement, nil) == SQLITE_OK {
            if let hash = deviceHash {
                sqlite3_bind_text(queryStatement, 1, (hash as NSString).utf8String, -1, nil)
            }
            
            while sqlite3_step(queryStatement) == SQLITE_ROW {
                let id = Self.columnString(queryStatement, 0)
                let deviceHash = Self.columnString(queryStatement, 1)
                let appName = Self.columnString(queryStatement, 2)
                let title = Self.columnString(queryStatement, 3)
                let content = Self.columnString(queryStatement, 4)
                let timestamp = sqlite3_column_int64(queryStatement, 5)
                
                messages.append(NotificationMessage(
                    id: id,
                    deviceHash: deviceHash,
                    appName: appName,
                    title: title,
                    content: content,
                    timestamp: timestamp,
                    iconBase64: nil // Icon is loaded dynamically by the UI
                ))
            }
        } else {
            print("SELECT statement could not be prepared")
        }
        sqlite3_finalize(queryStatement)
        
        return messages
    }

    @discardableResult
    func insertClipboardHistory(_ content: String, direction: String, timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }

        let insertStatementString = "INSERT INTO clipboard_history (content, direction, timestamp) VALUES (?, ?, ?);"
        var insertStatement: OpaquePointer?
        var didInsert = false
        if sqlite3_prepare_v2(db, insertStatementString, -1, &insertStatement, nil) == SQLITE_OK {
            sqlite3_bind_text(insertStatement, 1, (content as NSString).utf8String, -1, nil)
            sqlite3_bind_text(insertStatement, 2, (direction as NSString).utf8String, -1, nil)
            sqlite3_bind_int64(insertStatement, 3, timestamp)
            didInsert = sqlite3_step(insertStatement) == SQLITE_DONE
        } else {
            print("INSERT clipboard history statement could not be prepared.")
        }
        sqlite3_finalize(insertStatement)
        return didInsert
    }

    func getClipboardHistory() -> [ClipboardHistoryItem] {
        let queryStatementString = "SELECT id, content, direction, timestamp FROM clipboard_history ORDER BY timestamp DESC"
        var queryStatement: OpaquePointer?
        var items: [ClipboardHistoryItem] = []

        if sqlite3_prepare_v2(db, queryStatementString, -1, &queryStatement, nil) == SQLITE_OK {
            while sqlite3_step(queryStatement) == SQLITE_ROW {
                let id = sqlite3_column_int64(queryStatement, 0)
                let content = Self.columnString(queryStatement, 1)
                let direction = Self.columnString(queryStatement, 2)
                let timestamp = sqlite3_column_int64(queryStatement, 3)
                items.append(
                    ClipboardHistoryItem(
                        id: id,
                        content: content,
                        direction: direction,
                        timestamp: timestamp
                    )
                )
            }
        } else {
            print("SELECT clipboard history statement could not be prepared.")
        }
        sqlite3_finalize(queryStatement)

        return items
    }

    private static func columnString(_ statement: OpaquePointer?, _ index: Int32) -> String {
        guard let value = sqlite3_column_text(statement, index) else { return "" }
        return String(cString: value)
    }
    
    func getIconURL(for appName: String) -> URL? {
        let iconPath = (iconsDir as NSString).appendingPathComponent("\(appName).png")
        if FileManager.default.fileExists(atPath: iconPath) {
            return URL(fileURLWithPath: iconPath)
        }
        return nil
    }
}
