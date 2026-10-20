// swift-tools-version: 6.0

// The iOS app as a Swift package, for xtool on Linux (`xtool dev` in this folder); on macOS, project.yml builds the same sources with Xcode.
// ClientCore comes from CI: ./fetch-clientcore.sh puts it in Frameworks/.
import PackageDescription

let package = Package(
    name: "ShopArchive",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "ShopArchive", targets: ["ShopArchive"]),
    ],
    targets: [
        .target(
            name: "ShopArchive",
            dependencies: ["ClientCore"],
            path: "ShopArchive",
            // The text goes into the app itself (xtool.yml resources), where Xcode puts it too, not into a SwiftPM resource bundle.
            // The icon catalog needs actool, which Linux has not: CI compiles it to Assets.car (fetch-clientcore.sh, xtool.yml resources).
            exclude: ["en.lproj", "lo.lproj", "th.lproj", "Assets.xcassets"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .binaryTarget(name: "ClientCore", path: "Frameworks/ClientCore.xcframework"),
    ]
)
