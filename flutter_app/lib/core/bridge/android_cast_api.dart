import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

import 'cast_pigeon_api.dart';

class AndroidCastApi implements CastPigeonApi {
  AndroidCastApi({MethodChannel? methodChannel, EventChannel? eventChannel})
    : _methods =
          methodChannel ?? const MethodChannel('castpigeon.android/methods'),
      _events =
          eventChannel ?? const EventChannel('castpigeon.android/snapshots') {
    _eventSubscription = _events.receiveBroadcastStream().listen((event) {
      if (event is String) {
        _emitSnapshot(event);
      }
    });
    unawaited(refresh());
  }

  final MethodChannel _methods;
  final EventChannel _events;
  final _snapshotController = StreamController<CastPigeonSnapshot>.broadcast();
  StreamSubscription<Object?>? _eventSubscription;
  CastPigeonSnapshot _latestSnapshot = CastPigeonSnapshot.empty;

  final Map<String, String?> _iconCache = <String, String?>{};
  final Map<String, String?> _historyIconCache = <String, String?>{};

  @override
  Stream<CastPigeonSnapshot> get snapshotStream => _snapshotController.stream;

  @override
  Future<void> refresh() async {
    final payload = await _methods.invokeMethod<String>('snapshot');
    if (payload != null) {
      _emitSnapshot(payload);
    }
  }

  @override
  Future<bool> startPairing() => _boolMethod('startPairing');

  @override
  Future<bool> startWorking() => _boolMethod('startWorking');

  @override
  Future<bool> stop() => _boolMethod('stop');

  @override
  Future<bool> setRole(DeviceRole role) {
    return _boolMethod('setRole', <String, Object?>{'role': role.code});
  }

  @override
  Future<bool> requestBinding(CastDevice device) {
    return _boolMethod('requestBinding', _deviceArguments(device));
  }

  @override
  Future<bool> verifyBinding(CastDevice device, String pin) {
    return _boolMethod('verifyBinding', <String, Object?>{
      ..._deviceArguments(device),
      'targetHash': device.hash,
      'pin': pin,
    });
  }

  @override
  Future<bool> cancelPairingPrompt() => _boolMethod('cancelPairingPrompt');

  @override
  Future<bool> approvePairingRequest() => _boolMethod('approvePairingRequest');

  @override
  Future<bool> rejectPairingRequest() => _boolMethod('rejectPairingRequest');

  @override
  Future<bool> removeBoundDevice(String hash) {
    return _boolMethod('removeBoundDevice', <String, Object?>{'hash': hash});
  }

  @override
  Future<bool> renameBoundDevice(String hash, String name) async => false;

  @override
  Future<bool> setNotificationSharing(String hash, bool enabled) {
    return _boolMethod('setNotificationSharing', <String, Object?>{
      'hash': hash,
      'enabled': enabled,
    });
  }

  @override
  Future<bool> setShowSystemApps(bool show) {
    return _boolMethod('setShowSystemApps', <String, Object?>{'show': show});
  }

  @override
  Future<bool> setAppSyncEnabled(String packageName, bool enabled) {
    return _boolMethod('setAppSyncEnabled', <String, Object?>{
      'packageName': packageName,
      'enabled': enabled,
    });
  }

  @override
  Future<bool> sendFile(CastDevice device) {
    return _boolMethod('sendFile', _deviceArguments(device));
  }

  @override
  Future<String?> appIconBase64(String packageName) async {
    if (packageName.isEmpty) {
      return null;
    }
    if (_iconCache.containsKey(packageName)) {
      return _iconCache[packageName];
    }
    final value = await _methods.invokeMethod<String>(
      'appIconBase64',
      <String, Object?>{'packageName': packageName},
    );
    _iconCache[packageName] = value;
    return value;
  }

  @override
  Future<String?> historyIconBase64(String appName) async {
    if (appName.isEmpty) {
      return null;
    }
    if (_historyIconCache.containsKey(appName)) {
      return _historyIconCache[appName];
    }
    final value = await _methods.invokeMethod<String>(
      'historyIconBase64',
      <String, Object?>{'appName': appName},
    );
    _historyIconCache[appName] = value;
    return value;
  }

  @override
  Future<bool> copyClipboardHistory(String content) {
    return _boolMethod('copyClipboardHistory', <String, Object?>{
      'content': content,
    });
  }

  @override
  Future<bool> selectPrivilegeMode(String mode) {
    return _boolMethod('selectPrivilegeMode', <String, Object?>{'mode': mode});
  }

  @override
  Future<bool> checkUpdate() => _boolMethod('checkUpdate');

  @override
  Future<bool> refreshUpdateHistory() => _boolMethod('refreshUpdateHistory');

  @override
  Future<bool> downloadRelease(String tagName) {
    return _boolMethod('downloadRelease', <String, Object?>{
      'tagName': tagName,
    });
  }

  @override
  Future<bool> installRelease(String tagName) {
    return _boolMethod('installRelease', <String, Object?>{'tagName': tagName});
  }

  @override
  Future<bool> sendTestNotification() => _boolMethod('sendTestNotification');

  @override
  Future<bool> openBluetoothPrivacySettings() async => false;

  @override
  Future<bool> insertDebugLog(String message) async => false;

  @override
  Future<bool> clearDebugLogs() async => false;

  @override
  void dispose() {
    unawaited(_eventSubscription?.cancel());
    _snapshotController.close();
  }

  Future<bool> _boolMethod(String method, [Map<String, Object?>? arguments]) {
    return _methods
        .invokeMethod<bool>(method, arguments)
        .then((value) => value ?? false);
  }

  void _emitSnapshot(String payload) {
    final decoded = jsonDecode(payload);
    if (decoded is Map<String, Object?>) {
      _latestSnapshot = CastPigeonSnapshot.fromJson(decoded);
    } else if (decoded is Map) {
      _latestSnapshot = CastPigeonSnapshot.fromJson(
        decoded.cast<String, Object?>(),
      );
    }
    _snapshotController.add(_latestSnapshot);
  }

  Map<String, Object?> _deviceArguments(CastDevice device) {
    return <String, Object?>{
      'deviceName': device.deviceName,
      'role': device.role,
      'hash': device.hash,
      'ipAddress': device.ipAddress,
      'filePort': device.filePort,
      'deviceType': device.deviceType,
      'lanReachable': device.lanReachable,
    };
  }
}
