import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
  private let castPigeonBridge = MacOSCastPigeonBridge()

  override func awakeFromNib() {
    let flutterViewController = FlutterViewController()
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)
    configureWindowChrome()

    RegisterGeneratedPlugins(registry: flutterViewController)
    castPigeonBridge.register(with: flutterViewController)

    super.awakeFromNib()
  }

  private func configureWindowChrome() {
    titleVisibility = .hidden
    titlebarAppearsTransparent = true
    styleMask.insert(.fullSizeContentView)
    isMovableByWindowBackground = true
  }
}
