import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';

import 'cast_pigeon_api.dart';

typedef _NativeInit = Int32 Function();
typedef _DartInit = int Function();
typedef _NativeInitializeDartApi = Int32 Function(Pointer<Void>);
typedef _DartInitializeDartApi = int Function(Pointer<Void>);
typedef _NativeRegisterStatePort = Int32 Function(Int64);
typedef _DartRegisterStatePort = int Function(int);
typedef _NativeGetConnectionState = Int32 Function();
typedef _DartGetConnectionState = int Function();
typedef _NativeIntCommand = Int32 Function();
typedef _DartIntCommand = int Function();
typedef _NativeSetInt = Int32 Function(Int32);
typedef _DartSetInt = int Function(int);
typedef _NativeOneString = Int32 Function(Pointer<Utf8>);
typedef _DartOneString = int Function(Pointer<Utf8>);
typedef _NativeStringBool = Int32 Function(Pointer<Utf8>, Int32);
typedef _DartStringBool = int Function(Pointer<Utf8>, int);
typedef _NativeThreeStrings =
    Int32 Function(Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef _DartThreeStrings =
    int Function(Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef _NativeRequestBinding =
    Int32 Function(Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef _DartRequestBinding =
    int Function(Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef _NativeStringCommand = Pointer<Utf8> Function();
typedef _DartStringCommand = Pointer<Utf8> Function();
typedef _NativeFreeString = Void Function(Pointer<Utf8>);
typedef _DartFreeString = void Function(Pointer<Utf8>);

class FFICastApi implements CastPigeonApi {
  FFICastApi({DynamicLibrary? library}) : _library = library ?? _openLibrary() {
    _initialize = _library.lookupFunction<_NativeInit, _DartInit>(
      'castpigeon_initialize',
    );
    _initializeDartApi = _library
        .lookupFunction<_NativeInitializeDartApi, _DartInitializeDartApi>(
          'castpigeon_initialize_dart_api',
        );
    _registerStatePort = _library
        .lookupFunction<_NativeRegisterStatePort, _DartRegisterStatePort>(
          'castpigeon_register_state_port',
        );
    _getConnectionState = _library
        .lookupFunction<_NativeGetConnectionState, _DartGetConnectionState>(
          'castpigeon_get_connection_state',
        );
    _setRole = _library.lookupFunction<_NativeSetInt, _DartSetInt>(
      'castpigeon_set_role',
    );
    _startPairing = _library.lookupFunction<_NativeIntCommand, _DartIntCommand>(
      'castpigeon_start_pairing',
    );
    _startWorking = _library.lookupFunction<_NativeIntCommand, _DartIntCommand>(
      'castpigeon_start_working',
    );
    _stopDiscovery = _library
        .lookupFunction<_NativeIntCommand, _DartIntCommand>(
          'castpigeon_stop_discovery',
        );
    _snapshotJson = _library
        .lookupFunction<_NativeStringCommand, _DartStringCommand>(
          'castpigeon_snapshot_json',
        );
    _requestBinding = _library
        .lookupFunction<_NativeRequestBinding, _DartRequestBinding>(
          'castpigeon_request_binding',
        );
    _verifyBinding = _library
        .lookupFunction<_NativeThreeStrings, _DartThreeStrings>(
          'castpigeon_verify_binding',
        );
    _cancelPairingPrompt = _library
        .lookupFunction<_NativeIntCommand, _DartIntCommand>(
          'castpigeon_cancel_pairing_prompt',
        );
    _removeBoundDevice = _library
        .lookupFunction<_NativeOneString, _DartOneString>(
          'castpigeon_remove_bound_device',
        );
    _setNotificationSharing = _library
        .lookupFunction<_NativeStringBool, _DartStringBool>(
          'castpigeon_set_notification_sharing',
        );
    _setShowSystemApps = _library.lookupFunction<_NativeSetInt, _DartSetInt>(
      'castpigeon_set_show_system_apps',
    );
    _setAppSyncEnabled = _library
        .lookupFunction<_NativeStringBool, _DartStringBool>(
          'castpigeon_set_app_sync_enabled',
        );
    _freeString = _library.lookupFunction<_NativeFreeString, _DartFreeString>(
      'free_string_pointer',
    );

    _initialize();
    _initializeDartApi(NativeApi.initializeApiDLData);
    _statePort = ReceivePort('CastPigeon state');
    _statePort.listen((message) {
      if (message is int) {
        _emitSnapshot();
      }
    });
    _registerStatePort(_statePort.sendPort.nativePort);
    _emitSnapshot();
    _pollTimer = Timer.periodic(
      const Duration(milliseconds: 500),
      (_) => _emitSnapshot(),
    );
  }

  final DynamicLibrary _library;
  late final _DartInit _initialize;
  late final _DartInitializeDartApi _initializeDartApi;
  late final _DartRegisterStatePort _registerStatePort;
  late final _DartGetConnectionState _getConnectionState;
  late final _DartSetInt _setRole;
  late final _DartIntCommand _startPairing;
  late final _DartIntCommand _startWorking;
  late final _DartIntCommand _stopDiscovery;
  late final _DartStringCommand _snapshotJson;
  late final _DartRequestBinding _requestBinding;
  late final _DartThreeStrings _verifyBinding;
  late final _DartIntCommand _cancelPairingPrompt;
  late final _DartOneString _removeBoundDevice;
  late final _DartStringBool _setNotificationSharing;
  late final _DartSetInt _setShowSystemApps;
  late final _DartStringBool _setAppSyncEnabled;
  late final _DartFreeString _freeString;
  late final ReceivePort _statePort;

  final _snapshotController = StreamController<CastPigeonSnapshot>.broadcast();
  Timer? _pollTimer;
  CastPigeonSnapshot _latestSnapshot = CastPigeonSnapshot.empty;

  @override
  Stream<CastPigeonSnapshot> get snapshotStream => _snapshotController.stream;

  @override
  Future<void> refresh() async {
    _emitSnapshot();
  }

  @override
  Future<bool> startPairing() async {
    return _runCommand(_startPairing);
  }

  @override
  Future<bool> startWorking() async {
    return _runCommand(_startWorking);
  }

  @override
  Future<bool> stop() async {
    return _runCommand(_stopDiscovery);
  }

  @override
  Future<bool> setRole(DeviceRole role) async {
    final ok = _setRole(role.code) == 1;
    _emitSnapshot();
    return ok;
  }

  @override
  Future<bool> requestBinding(CastDevice device) async {
    return _withNativeStrings(
      [device.hash, device.deviceName, device.role, device.ipAddress],
      (pointers) =>
          _requestBinding(pointers[0], pointers[1], pointers[2], pointers[3]) ==
          1,
    );
  }

  @override
  Future<bool> verifyBinding(CastDevice device, String pin) async {
    return _withNativeStrings([
      device.hash,
      pin,
      device.ipAddress,
    ], (pointers) => _verifyBinding(pointers[0], pointers[1], pointers[2]) == 1);
  }

  @override
  Future<bool> cancelPairingPrompt() async {
    return _runCommand(_cancelPairingPrompt);
  }

  @override
  Future<bool> approvePairingRequest() async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> rejectPairingRequest() async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> removeBoundDevice(String hash) async {
    return _withNativeStrings([
      hash,
    ], (pointers) => _removeBoundDevice(pointers[0]) == 1);
  }

  @override
  Future<bool> renameBoundDevice(String hash, String name) async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> setNotificationSharing(String hash, bool enabled) async {
    return _withNativeStrings(
      [hash],
      (pointers) => _setNotificationSharing(pointers[0], enabled ? 1 : 0) == 1,
    );
  }

  @override
  Future<bool> setShowSystemApps(bool show) async {
    final ok = _setShowSystemApps(show ? 1 : 0) == 1;
    _emitSnapshot();
    return ok;
  }

  @override
  Future<bool> setAppSyncEnabled(String packageName, bool enabled) async {
    return _withNativeStrings([
      packageName,
    ], (pointers) => _setAppSyncEnabled(pointers[0], enabled ? 1 : 0) == 1);
  }

  @override
  Future<bool> sendFile(CastDevice device) async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<String?> appIconBase64(String packageName) async {
    return null;
  }

  @override
  Future<String?> historyIconBase64(String appName) async {
    return null;
  }

  @override
  Future<bool> copyClipboardHistory(String content) async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> selectPrivilegeMode(String mode) async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> checkUpdate() async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> refreshUpdateHistory() async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> downloadRelease(String tagName) async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> installRelease(String tagName) async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> sendTestNotification() async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> openBluetoothPrivacySettings() async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> insertDebugLog(String message) async {
    _emitSnapshot();
    return false;
  }

  @override
  Future<bool> clearDebugLogs() async {
    _emitSnapshot();
    return false;
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _statePort.close();
    _snapshotController.close();
  }

  bool _runCommand(int Function() command) {
    final ok = command() == 1;
    _emitSnapshot();
    return ok;
  }

  void _emitSnapshot() {
    _getConnectionState();
    final pointer = _snapshotJson();
    if (pointer == nullptr) {
      _snapshotController.add(_latestSnapshot);
      return;
    }
    try {
      final payload = pointer.toDartString();
      final decoded = jsonDecode(payload);
      if (decoded is Map<String, Object?>) {
        _latestSnapshot = CastPigeonSnapshot.fromJson(decoded);
      } else if (decoded is Map) {
        _latestSnapshot = CastPigeonSnapshot.fromJson(
          decoded.cast<String, Object?>(),
        );
      }
      _snapshotController.add(_latestSnapshot);
    } finally {
      _freeString(pointer);
    }
  }

  bool _withNativeStrings(
    List<String> values,
    bool Function(List<Pointer<Utf8>> pointers) block,
  ) {
    final pointers = values
        .map((value) => value.toNativeUtf8())
        .toList(growable: false);
    try {
      final ok = block(pointers);
      _emitSnapshot();
      return ok;
    } finally {
      for (final pointer in pointers) {
        calloc.free(pointer);
      }
    }
  }

  static DynamicLibrary _openLibrary() {
    if (Platform.isMacOS) {
      final executableDirectory = File(
        Platform.resolvedExecutable,
      ).parent.absolute.path;
      final candidates = <String>{
        '$executableDirectory/../Frameworks/libcastpigeon_core.dylib',
        '$executableDirectory/libcastpigeon_core.dylib',
        ..._macosBuildLibraryCandidates(executableDirectory),
        ..._macosBuildLibraryCandidates(Directory.current.absolute.path),
      };
      for (final path in candidates) {
        final file = File(path);
        if (file.existsSync()) {
          return DynamicLibrary.open(file.absolute.path);
        }
      }
      return DynamicLibrary.open('libcastpigeon_core.dylib');
    }
    if (Platform.isLinux) {
      return DynamicLibrary.open('libcastpigeon_core.so');
    }
    if (Platform.isWindows) {
      return DynamicLibrary.open('castpigeon_core.dll');
    }
    if (Platform.isIOS) {
      return DynamicLibrary.process();
    }
    if (Platform.isAndroid) {
      return DynamicLibrary.open('libcastpigeon_core.so');
    }
    throw UnsupportedError('Unsupported platform for CastPigeon FFI');
  }

  static Iterable<String> _macosBuildLibraryCandidates(String startPath) sync* {
    var directory = Directory(startPath);
    while (true) {
      yield '${directory.path}/kmp_core/build/bin/macosArm64/debugShared/libcastpigeon_core.dylib';
      yield '${directory.path}/../kmp_core/build/bin/macosArm64/debugShared/libcastpigeon_core.dylib';
      final parent = directory.parent;
      if (parent.path == directory.path) {
        break;
      }
      directory = parent;
    }
  }
}
