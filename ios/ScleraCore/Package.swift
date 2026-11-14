// swift-tools-version:5.9
// Pure-Swift measurement core shared by the iOS app. No UIKit/AVFoundation, so it builds and
// tests on a Mac with only the Command Line Tools: `swift test` (CLAUDE.md §3: keep the maths
// unit-testable on its own). Line-for-line port of the Android app's colour/segmentation/quality/decision.
import PackageDescription

let package = Package(
    name: "ScleraCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "ScleraCore", targets: ["ScleraCore"])],
    targets: [
        .target(name: "ScleraCore"),
        .testTarget(name: "ScleraCoreTests", dependencies: ["ScleraCore"]),
    ]
)
