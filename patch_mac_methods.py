import re

with open('/Users/vincent/Desktop/CastPigeon/macosApp/CastPigeonMac/ViewModels/MainViewModel.swift', 'r') as f:
    content = f.read()

target = """    func sendMockMessage(_ msg: String) {
        if let dataChar = gattCharacteristic, let data = msg.data(using: .utf8) {
            peripheralManager.updateValue(data, for: dataChar, onSubscribedCentrals: subscribedCentrals)
        }
    }"""

new_methods = """    func sendMockMessage(_ msg: String) {
        if let dataChar = gattCharacteristic, let data = msg.data(using: .utf8) {
            peripheralManager.updateValue(data, for: dataChar, onSubscribedCentrals: subscribedCentrals)
        }
    }

    private func checkClipboard() {
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
    }"""

if target in content:
    content = content.replace(target, new_methods)
    with open('/Users/vincent/Desktop/CastPigeon/macosApp/CastPigeonMac/ViewModels/MainViewModel.swift', 'w') as f:
        f.write(content)
    print("Replaced methods successfully.")
else:
    print("Failed to replace methods.")
