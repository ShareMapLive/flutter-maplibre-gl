// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "sharemap_maplibre_gl",
    platforms: [
        .iOS("15.5"),
    ],
    products: [
        .library(name: "sharemap-maplibre-gl", targets: ["sharemap_maplibre_gl"])
    ],
    dependencies: [
        // When updating the dependency version,
        // make sure to also update the version in sharemap_maplibre_gl.podspec.
        .package(url: "https://github.com/maplibre/maplibre-gl-native-distribution.git", exact: "7.0.0"),
    ],
    targets: [
        .target(
            name: "sharemap_maplibre_gl",
            dependencies: [
                "sharemap_maplibre_gl_objc",
                .product(name: "MapLibre", package: "maplibre-gl-native-distribution")
            ]
        ),
        // Catches the Objective-C exceptions MapLibre raises, which Swift cannot.
        .target(name: "sharemap_maplibre_gl_objc")
    ]
)
