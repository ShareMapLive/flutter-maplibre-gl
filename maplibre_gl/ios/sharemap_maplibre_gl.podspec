#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'sharemap_maplibre_gl'
  s.version          = '0.28.0'
  s.summary          = 'MapLibre GL Flutter plugin'
  s.description      = <<-DESC
MapLibre GL Flutter plugin.
                       DESC
  s.homepage         = 'https://maplibre.org'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'MapLibre' => 'info@maplibre.org' }
  s.source           = { :path => '.' }
  s.source_files = 'sharemap_maplibre_gl/Sources/maplibre_gl/**/*', 'sharemap_maplibre_gl/Sources/sharemap_maplibre_gl_objc/**/*'
  s.public_header_files = 'sharemap_maplibre_gl/Sources/sharemap_maplibre_gl_objc/include/*.h'
  s.dependency 'Flutter'
  # When updating the dependency version,
  # make sure to also update the version in Package.swift.
  s.dependency 'MapLibre', '7.0.0'
  s.swift_version = '5.0'
  s.ios.deployment_target = '15.5'
end
