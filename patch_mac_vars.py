import re

with open('/Users/vincent/Desktop/CastPigeon/macosApp/CastPigeonMac/ViewModels/MainViewModel.swift', 'r') as f:
    content = f.read()

target1 = """    @Published var receivedImage: Data? = nil
    
    private var receiveBuffers: [UUID: Data] = [:]"""

replacement1 = """    @Published var receivedImage: Data? = nil
    
    private var clipboardTimer: Timer?
    private var lastClipboardChangeCount: Int = NSPasteboard.general.changeCount
    
    private var receiveBuffers: [UUID: Data] = [:]"""

if target1 in content:
    content = content.replace(target1, replacement1)
    print("Replaced vars.")
else:
    print("Failed to replace vars.")

target2 = """        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        
        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            self?.checkClipboard()
        }"""

if target2 not in content:
    old_init = """        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)"""
    new_init = """        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        
        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            self?.checkClipboard()
        }"""
    if old_init in content:
        content = content.replace(old_init, new_init)
        print("Replaced init.")
    else:
        print("Failed to replace init.")

target3 = """    private func applyRoleAndMode() {"""

if "private func checkClipboard() {" not in content:
    if target3 in content:
        new_methods = """    private func checkClipboard() {
        guard workMode == .working else { return }
        let currentCount = NSPasteboard.general.changeCount
        guard currentCount != lastClipboardChangeCount else { return }
        lastClipboardChangeCount = currentCount
        
        if let text = NSPasteboard.general.string(forType: .string) {
            let payload = "CLIP|" + text
            sendClipboardPayload(payload)
        }
    }
    
    private func sendClipboardPayload(_ payload: String) {
        guard let data = payload.data(using: .utf8) else { return }
        
        // If Mac is Peripheral
        if let dataChar = gattCharacteristic {
            peripheralManager.updateValue(data, for: dataChar, onSubscribedCentrals: subscribedCentrals)
        }
        
        // If Mac is Central
        for peripheral in connectedPeripherals.values {
            if let service = peripheral.services?.first(where: { $0.uuid == serviceUuid }),
               let char = service.characteristics?.first(where: { $0.uuid == handshakeCharUuid }) {
                peripheral.writeValue(data, for: char, type: .withResponse)
            }
        }
    }
    
    private func applyRoleAndMode() {"""
        content = content.replace(target3, new_methods)
        print("Replaced methods.")
    else:
        print("Failed to replace methods.")

with open('/Users/vincent/Desktop/CastPigeon/macosApp/CastPigeonMac/ViewModels/MainViewModel.swift', 'w') as f:
    f.write(content)

