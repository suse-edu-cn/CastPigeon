import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
  private let castPigeonBridge = MacOSCastPigeonBridge()

  override func awakeFromNib() {
    let flutterViewController = FlutterViewController()
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)

    RegisterGeneratedPlugins(registry: flutterViewController)
    castPigeonBridge.register(with: flutterViewController)

    super.awakeFromNib()
  }
}
