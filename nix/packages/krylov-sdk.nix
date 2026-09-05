{ androidenv }:
(androidenv.composeAndroidPackages {
  platformVersions = [ "35" ];
  buildToolsVersions = [ "37.0.0" ];
  includeNDK = true;
  ndkVersions = [ "29.0.14206865" ];
  includeEmulator = true;
  includeSystemImages = true;
  systemImageTypes = [ "default" ];
  abiVersions = [ "x86_64" ];
  includeSources = false;
}).androidsdk
