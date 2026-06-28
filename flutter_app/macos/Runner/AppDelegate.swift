import Cocoa
import FlutterMacOS

@main
class AppDelegate: FlutterAppDelegate {
  override func applicationDidFinishLaunching(_ notification: Notification) {
    super.applicationDidFinishLaunching(notification)
    applyBundledApplicationIcon()
  }

  override func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
    return true
  }

  override func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
    return true
  }

  private func applyBundledApplicationIcon() {
    guard let iconURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
          let image = NSImage(contentsOf: iconURL) else {
      return
    }
    NSApplication.shared.applicationIconImage = image
  }
}
