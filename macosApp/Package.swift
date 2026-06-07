// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CastPigeonMac",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "CastPigeonMac",
            path: "CastPigeonMac",
            exclude: ["Info.plist"]
        )
    ]
)
