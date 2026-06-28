import 'dart:io';

import 'android_cast_api.dart';
import 'cast_pigeon_api.dart';
import 'ffi_cast_api.dart';
import 'macos_cast_api.dart';

CastPigeonApi createCastPigeonApi() {
  if (Platform.isAndroid) {
    return AndroidCastApi();
  }
  if (Platform.isMacOS) {
    return MacOSCastApi();
  }
  return FFICastApi();
}
