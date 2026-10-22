// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "ShelloKit",
    platforms: [.iOS(.v16)],
    products: [.library(name: "ShelloTerminal", targets: ["ShelloTerminal"]), .library(name: "ShelloCore", targets: ["ShelloCore"]), .library(name: "ShelloSSH", targets: ["ShelloSSH"]), .library(name: "ShelloTerminalUI", targets: ["ShelloTerminalUI"]), .library(name: "ShelloServices", targets: ["ShelloServices"])],
    targets: [
        .binaryTarget(name: "ShelloNative", path: "Frameworks/ShelloNative.xcframework"),
        .target(name: "ShelloTerminal", dependencies: ["ShelloNative"]),
        .target(name: "ShelloCore", dependencies: ["ShelloTerminal"]),
        .target(name: "ShelloSSH", dependencies: ["ShelloNative"]),
        .target(name: "ShelloTerminalUI", dependencies: ["ShelloTerminal"]),
        .target(name: "ShelloServices", dependencies: ["ShelloCore", "ShelloSSH", "ShelloTerminal"]),
        .testTarget(name: "ShelloTerminalTests", dependencies: ["ShelloTerminal"]),
        .testTarget(name: "ShelloTerminalUITests", dependencies: ["ShelloTerminalUI", "ShelloTerminal"]),
        .testTarget(name: "ShelloServicesTests", dependencies: ["ShelloServices", "ShelloCore", "ShelloSSH", "ShelloTerminal"]),
        .testTarget(name: "ShelloCoreTests", dependencies: ["ShelloCore"]),
        .testTarget(name: "ShelloSSHTests", dependencies: ["ShelloSSH", "ShelloCore", "ShelloTerminal"]),
    ]
)
