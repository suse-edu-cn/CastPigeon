import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import '../core/bridge/cast_pigeon_api.dart';

enum AppTab {
  dashboard('主页', '工作台', Icons.speed_rounded),
  history('发送历史', '历史记录', Icons.history_rounded),
  settings('控制台', '设备管理', Icons.devices_rounded),
  info('信息', '自动更新', Icons.download_rounded);

  const AppTab(this.mobileTitle, this.desktopTitle, this.icon);

  final String mobileTitle;
  final String desktopTitle;
  final IconData icon;
}

class CastPigeonHome extends StatefulWidget {
  const CastPigeonHome({super.key, required this.api});

  final CastPigeonApi api;

  @override
  State<CastPigeonHome> createState() => _CastPigeonHomeState();
}

class _CastPigeonHomeState extends State<CastPigeonHome> {
  CastPigeonSnapshot _snapshot = CastPigeonSnapshot.empty;
  AppTab _tab = AppTab.dashboard;
  int _tabSlideDirection = 1;
  bool _showMacPairingSheet = false;
  StreamSubscription<CastPigeonSnapshot>? _subscription;

  @override
  void initState() {
    super.initState();
    _subscription = widget.api.snapshotStream.listen((snapshot) {
      if (mounted) {
        final previousBoundHashes = _snapshot.boundDevices
            .map((device) => device.hash.toUpperCase())
            .toSet();
        final nextBoundHashes = snapshot.boundDevices
            .map((device) => device.hash.toUpperCase())
            .toSet();
        final pairingCompleted =
            _showMacPairingSheet &&
            nextBoundHashes.difference(previousBoundHashes).isNotEmpty;
        setState(() {
          _snapshot = snapshot;
          if (pairingCompleted) {
            _showMacPairingSheet = false;
          }
        });
      }
    });
    unawaited(widget.api.refresh());
  }

  @override
  void dispose() {
    _subscription?.cancel();
    widget.api.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isDesktop = _isDesktopPlatform;
    return Scaffold(
      backgroundColor: const Color(0xfff7f8f8),
      body: SafeArea(
        child: isDesktop
            ? _DesktopShell(
                api: widget.api,
                snapshot: _snapshot,
                selected: _tab,
                onSelect: _selectTab,
                showPairingSheet:
                    _showMacPairingSheet ||
                    _snapshot.pinDisplay != null ||
                    _snapshot.pinInputDevice != null,
                onStartPairing: () {
                  setState(() => _showMacPairingSheet = true);
                  unawaited(widget.api.startPairing());
                },
                onClosePairing: () {
                  setState(() => _showMacPairingSheet = false);
                  unawaited(widget.api.stop());
                },
              )
            : Stack(
                children: [
                  Positioned.fill(child: _buildMobileTabSwitcher()),
                  Align(
                    alignment: Alignment.bottomCenter,
                    child: _BottomNavigationBar(
                      selected: _tab,
                      onSelect: _selectTab,
                    ),
                  ),
                  _SnapshotDialogs(api: widget.api, snapshot: _snapshot),
                ],
              ),
      ),
    );
  }

  void _selectTab(AppTab tab) {
    if (tab == _tab) {
      return;
    }
    setState(() {
      _tabSlideDirection = tab.index > _tab.index ? 1 : -1;
      _tab = tab;
    });
  }

  Widget _buildMobileTabSwitcher() {
    final direction = _tabSlideDirection.toDouble();
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 280),
      reverseDuration: const Duration(milliseconds: 220),
      switchInCurve: Curves.easeOutCubic,
      switchOutCurve: Curves.easeInCubic,
      transitionBuilder: (child, animation) {
        final isIncoming = child.key == ValueKey(_tab);
        final begin = Offset(isIncoming ? direction : -direction, 0);
        final offsetAnimation = animation.drive(
          Tween<Offset>(
            begin: begin,
            end: Offset.zero,
          ).chain(CurveTween(curve: Curves.easeOutCubic)),
        );
        return SlideTransition(
          position: offsetAnimation,
          child: FadeTransition(opacity: animation, child: child),
        );
      },
      child: KeyedSubtree(key: ValueKey(_tab), child: _buildMobileTab()),
    );
  }

  Widget _buildMobileTab() {
    return switch (_tab) {
      AppTab.dashboard => _DashboardPage(api: widget.api, snapshot: _snapshot),
      AppTab.history => _HistoryPage(api: widget.api, snapshot: _snapshot),
      AppTab.settings => _SettingsPage(api: widget.api, snapshot: _snapshot),
      AppTab.info => _InfoPage(api: widget.api, snapshot: _snapshot),
    };
  }
}

bool get _isDesktopPlatform =>
    Platform.isMacOS || Platform.isWindows || Platform.isLinux;

class _BottomNavigationBar extends StatelessWidget {
  const _BottomNavigationBar({required this.selected, required this.onSelect});

  final AppTab selected;
  final ValueChanged<AppTab> onSelect;

  @override
  Widget build(BuildContext context) {
    const radius = 24.0;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.92),
          border: Border.all(color: const Color(0x1a1f2b27)),
          borderRadius: BorderRadius.circular(radius),
          boxShadow: const [
            BoxShadow(
              color: Color(0x1a0e2019),
              blurRadius: 28,
              offset: Offset(0, 16),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Padding(
          padding: const EdgeInsets.all(6),
          child: Row(
            children: [
              for (final tab in AppTab.values)
                Expanded(
                  child: Tooltip(
                    message: tab.mobileTitle,
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 220),
                      curve: Curves.easeOutCubic,
                      height: 48,
                      decoration: BoxDecoration(
                        color: selected == tab
                            ? const Color(0xffe0eee9)
                            : Colors.transparent,
                        borderRadius: BorderRadius.circular(radius - 6),
                      ),
                      child: IconButton(
                        isSelected: selected == tab,
                        onPressed: () => onSelect(tab),
                        icon: Icon(tab.icon),
                        color: const Color(0xff53605a),
                        selectedIcon: Icon(
                          tab.icon,
                          color: const Color(0xff156f5b),
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DesktopShell extends StatelessWidget {
  const _DesktopShell({
    required this.api,
    required this.snapshot,
    required this.selected,
    required this.onSelect,
    required this.showPairingSheet,
    required this.onStartPairing,
    required this.onClosePairing,
  });

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;
  final AppTab selected;
  final ValueChanged<AppTab> onSelect;
  final bool showPairingSheet;
  final VoidCallback onStartPairing;
  final VoidCallback onClosePairing;

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Row(
          children: [
            _DesktopSidebar(
              snapshot: snapshot,
              selected: selected,
              onSelect: onSelect,
            ),
            const VerticalDivider(width: 1),
            Expanded(
              child: switch (selected) {
                AppTab.dashboard => _DesktopDashboardPage(
                  api: api,
                  snapshot: snapshot,
                ),
                AppTab.history => _MacHistoryPage(api: api, snapshot: snapshot),
                AppTab.settings => _MacDevicesPage(
                  api: api,
                  snapshot: snapshot,
                  onStartPairing: onStartPairing,
                ),
                AppTab.info => _MacUpdatesPage(api: api, snapshot: snapshot),
              },
            ),
          ],
        ),
        if (showPairingSheet)
          _MacPairingSheetOverlay(
            api: api,
            snapshot: snapshot,
            onClose: onClosePairing,
          ),
      ],
    );
  }
}

class _DesktopSidebar extends StatelessWidget {
  const _DesktopSidebar({
    required this.snapshot,
    required this.selected,
    required this.onSelect,
  });

  final CastPigeonSnapshot snapshot;
  final AppTab selected;
  final ValueChanged<AppTab> onSelect;

  @override
  Widget build(BuildContext context) {
    final active = snapshot.workMode != WorkMode.idle;
    return Container(
      width: 248,
      color: Colors.white,
      padding: const EdgeInsets.fromLTRB(14, 16, 14, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: const Color(0xfff7f8f8),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: const Color(0x141f2b27)),
                ),
                clipBehavior: Clip.antiAlias,
                alignment: Alignment.center,
                child: Image.asset(
                  'macos/Runner/Assets.xcassets/AppIcon.appiconset/app_icon_128.png',
                  width: 32,
                  height: 32,
                  fit: BoxFit.contain,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('CastPigeon', style: _titleStyle(context)),
                    Text(
                      snapshot.localDeviceName,
                      style: _captionStyle(context),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),
          for (final tab in AppTab.values) ...[
            _DesktopNavButton(
              tab: tab,
              selected: selected == tab,
              onTap: () => onSelect(tab),
            ),
            const SizedBox(height: 4),
          ],
          const Spacer(),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: const Color(0xffeef3f1),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: const Color(0x1226332f)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        color: active
                            ? const Color(0xff4caf50)
                            : const Color(0xff9aa5a0),
                        shape: BoxShape.circle,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        snapshot.workModeLabel,
                        style: _mediumStyle(context),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  snapshot.connectionStateLabel,
                  style: _captionStyle(context),
                ),
                const SizedBox(height: 6),
                Text(
                  snapshot.localDeviceHash,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: _captionStyle(context),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _DesktopNavButton extends StatelessWidget {
  const _DesktopNavButton({
    required this.tab,
    required this.selected,
    required this.onTap,
  });

  final AppTab tab;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tab.desktopTitle,
      child: Material(
        color: selected ? const Color(0xffe0eee9) : Colors.transparent,
        borderRadius: BorderRadius.circular(8),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            height: 44,
            child: Row(
              children: [
                const SizedBox(width: 12),
                Icon(
                  tab.icon,
                  size: 20,
                  color: selected
                      ? const Color(0xff156f5b)
                      : const Color(0xff53605a),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    tab.desktopTitle,
                    style: _mediumStyle(context).copyWith(
                      color: selected
                          ? const Color(0xff156f5b)
                          : const Color(0xff26332f),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _DesktopDashboardPage extends StatelessWidget {
  const _DesktopDashboardPage({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(40, 40, 40, 24),
      children: [
        Text(
          'CastPigeon 工作台',
          style: Theme.of(
            context,
          ).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w800),
        ),
        if (snapshot.bluetoothPermissionDenied) ...[
          const SizedBox(height: 24),
          _MacBluetoothPermissionCard(api: api),
        ],
        const SizedBox(height: 30),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Flexible(
              child: _MacModeCard(
                title: '作为接收端 (Mac)',
                subtitle: '接收来自手机的推送通知和剪贴板。',
                icon: Icons.desktop_mac_rounded,
                selected: snapshot.role == DeviceRole.receiver,
                enabled: snapshot.workMode == WorkMode.idle,
                onTap: () => unawaited(api.setRole(DeviceRole.receiver)),
              ),
            ),
            const SizedBox(width: 40),
            Flexible(
              child: _MacModeCard(
                title: '作为发送端 (测试用)',
                subtitle: '向其他设备广播并发送数据。',
                icon: Icons.wifi_tethering_rounded,
                selected: snapshot.role == DeviceRole.sender,
                enabled: snapshot.workMode == WorkMode.idle,
                onTap: () => unawaited(api.setRole(DeviceRole.sender)),
              ),
            ),
          ],
        ),
        const SizedBox(height: 30),
        _SurfaceCard(
          child: Column(
            children: [
              Text(snapshot.connectionStateLabel, style: _titleStyle(context)),
              const SizedBox(height: 8),
              Text(
                snapshot.connectionStateDescription,
                style: _subtleStyle(context),
                textAlign: TextAlign.center,
              ),
              if (snapshot.isAnimating) ...[
                const SizedBox(height: 18),
                const SizedBox.square(
                  dimension: 28,
                  child: CircularProgressIndicator(strokeWidth: 2.6),
                ),
              ],
              const SizedBox(height: 18),
              if (snapshot.workMode == WorkMode.idle)
                FilledButton(
                  onPressed: snapshot.bluetoothPermissionDenied
                      ? () => unawaited(api.openBluetoothPrivacySettings())
                      : () => unawaited(api.startWorking()),
                  child: const SizedBox(
                    width: 180,
                    height: 40,
                    child: Center(child: Text('启动工作')),
                  ),
                )
              else
                FilledButton.tonal(
                  onPressed: () => unawaited(api.stop()),
                  child: const SizedBox(
                    width: 180,
                    height: 40,
                    child: Center(child: Text('停止并断开')),
                  ),
                ),
            ],
          ),
        ),
        if (snapshot.transferStatus != null) ...[
          const SizedBox(height: 14),
          _TransferCard(status: snapshot.transferStatus!),
        ],
        const SizedBox(height: 30),
        _MacDebugPanel(api: api, snapshot: snapshot),
      ],
    );
  }
}

class _MacBluetoothPermissionCard extends StatelessWidget {
  const _MacBluetoothPermissionCard({required this.api});

  final CastPigeonApi api;

  @override
  Widget build(BuildContext context) {
    return _SurfaceCard(
      color: const Color(0xfffff2df),
      borderColor: const Color(0x3dd47d00),
      child: Row(
        children: [
          const Icon(
            Icons.bluetooth_disabled_rounded,
            color: Color(0xffb65f00),
            size: 28,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('需要蓝牙权限', style: _titleStyle(context)),
                const SizedBox(height: 4),
                Text(
                  '请在系统设置中允许 CastPigeon 使用蓝牙，然后回到应用重新启动工作。',
                  style: _subtleStyle(context),
                ),
              ],
            ),
          ),
          FilledButton.tonal(
            onPressed: () => unawaited(api.openBluetoothPrivacySettings()),
            child: const Text('打开蓝牙权限设置'),
          ),
        ],
      ),
    );
  }
}

class _MacModeCard extends StatelessWidget {
  const _MacModeCard({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final IconData icon;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _SurfaceCard(
      color: selected ? const Color(0xff156f5b) : null,
      borderColor: selected ? const Color(0xff156f5b) : null,
      onTap: enabled ? onTap : null,
      child: SizedBox(
        width: 200,
        height: 160,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              icon,
              size: 40,
              color: selected ? Colors.white : const Color(0xff156f5b),
            ),
            const SizedBox(height: 12),
            Text(
              title,
              style: _titleStyle(
                context,
              ).copyWith(color: selected ? Colors.white : null),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 6),
            Text(
              subtitle,
              style: _subtleStyle(
                context,
              ).copyWith(color: selected ? Colors.white70 : null),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

class _MacDebugPanel extends StatelessWidget {
  const _MacDebugPanel({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final logText = snapshot.debugLogs.isEmpty
        ? '暂无日志'
        : snapshot.debugLogs.join('\n');
    return _SurfaceCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text('BLE 实时诊断日志：', style: _titleStyle(context)),
              Text('可选中复制', style: _captionStyle(context)),
              TextButton(
                onPressed: () =>
                    unawaited(api.insertDebugLog('这是一条手动插入的测试日志！')),
                child: const Text('插入测试日志'),
              ),
              TextButton(
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: logText));
                },
                child: const Text('复制全部'),
              ),
              TextButton(
                onPressed: () => unawaited(api.clearDebugLogs()),
                child: const Text('清空'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Container(
            height: 170,
            width: double.infinity,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.55),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: const Color(0x1226332f)),
            ),
            child: SingleChildScrollView(
              child: SelectableText(
                logText,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: snapshot.debugLogs.isEmpty
                      ? const Color(0xff68756f)
                      : const Color(0xff26332f),
                  fontFamily: 'Menlo',
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MacDevicesPage extends StatelessWidget {
  const _MacDevicesPage({
    required this.api,
    required this.snapshot,
    required this.onStartPairing,
  });

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;
  final VoidCallback onStartPairing;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(40, 40, 40, 24),
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '设备管理',
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            FilledButton.icon(
              onPressed: onStartPairing,
              icon: const Icon(Icons.add_rounded),
              label: const Text('配对新设备'),
            ),
          ],
        ),
        const SizedBox(height: 16),
        _SurfaceCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('已授权绑定的设备', style: _sectionStyle(context)),
              const SizedBox(height: 12),
              if (snapshot.boundDevices.isEmpty)
                Text('当前未绑定任何设备，请先配对。', style: _subtleStyle(context))
              else
                for (final entry in snapshot.boundDevices)
                  _MacBoundDeviceRow(
                    api: api,
                    entry: entry,
                    snapshot: snapshot,
                  ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        if (snapshot.transferStatus != null) ...[
          _TransferCard(status: snapshot.transferStatus!),
          const SizedBox(height: 14),
        ],
        _SurfaceCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('局域网在线设备', style: _sectionStyle(context)),
              const SizedBox(height: 12),
              if (snapshot.onlineDevices.isEmpty)
                Text('暂无局域网设备。启动工作或配对模式后会自动发现。', style: _subtleStyle(context))
              else
                for (final device in snapshot.onlineDevices)
                  _MacOnlineDeviceRow(
                    api: api,
                    snapshot: snapshot,
                    device: device,
                  ),
            ],
          ),
        ),
      ],
    );
  }
}

class _MacBoundDeviceRow extends StatelessWidget {
  const _MacBoundDeviceRow({
    required this.api,
    required this.entry,
    required this.snapshot,
  });

  final CastPigeonApi api;
  final BoundDevice entry;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final online = snapshot.connectedDeviceHashes
        .map((hash) => hash.toUpperCase())
        .contains(entry.hash.toUpperCase());
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Icon(_deviceIcon(entry.deviceType), color: const Color(0xff156f5b)),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(entry.name, style: _mediumStyle(context)),
                    ),
                    const SizedBox(width: 8),
                    Icon(
                      Icons.circle,
                      size: 8,
                      color: online ? const Color(0xff4caf50) : Colors.grey,
                    ),
                    const SizedBox(width: 4),
                    Text(online ? '在线' : '离线', style: _captionStyle(context)),
                  ],
                ),
                Text('Hash: ${entry.hash}', style: _subtleStyle(context)),
              ],
            ),
          ),
          TextButton(
            onPressed: () => unawaited(_showRenameBoundDeviceDialog(context)),
            child: const Text('重命名'),
          ),
          Text('通知', style: _captionStyle(context)),
          Switch(
            value: entry.notificationSharingEnabled,
            onChanged: (checked) =>
                unawaited(api.setNotificationSharing(entry.hash, checked)),
          ),
          IconButton(
            tooltip: '解绑',
            onPressed: () => unawaited(api.removeBoundDevice(entry.hash)),
            icon: const Icon(Icons.link_off_rounded, color: Color(0xffb3261e)),
          ),
        ],
      ),
    );
  }

  Future<void> _showRenameBoundDeviceDialog(BuildContext context) async {
    final controller = TextEditingController(text: entry.name);
    final name = await showDialog<String>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: const Text('重命名设备'),
          content: SizedBox(
            width: 260,
            child: TextField(
              controller: controller,
              autofocus: true,
              decoration: const InputDecoration(
                labelText: '设备名称',
                border: OutlineInputBorder(),
              ),
              onSubmitted: (value) => Navigator.of(context).pop(value),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(controller.text),
              child: const Text('保存'),
            ),
          ],
        );
      },
    );
    controller.dispose();
    final trimmed = name?.trim() ?? '';
    if (trimmed.isNotEmpty) {
      await api.renameBoundDevice(entry.hash, trimmed);
    }
  }
}

class _MacOnlineDeviceRow extends StatelessWidget {
  const _MacOnlineDeviceRow({
    required this.api,
    required this.snapshot,
    required this.device,
  });

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;
  final CastDevice device;

  @override
  Widget build(BuildContext context) {
    final bound = snapshot.boundDevices.any(
      (entry) => entry.hash.toUpperCase() == device.hash.toUpperCase(),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Icon(_deviceIcon(device.deviceType), color: const Color(0xff156f5b)),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(device.deviceName, style: _mediumStyle(context)),
                Text(
                  '${device.deviceType} / ${device.ipAddress} / 文件端口 ${device.filePort ?? "不可用"}',
                  style: _subtleStyle(context),
                ),
              ],
            ),
          ),
          if (snapshot.workMode == WorkMode.pairing && !bound)
            TextButton(
              onPressed: () => unawaited(api.requestBinding(device)),
              child: const Text('绑定'),
            ),
          TextButton.icon(
            onPressed: device.filePort == null || device.ipAddress.isEmpty
                ? null
                : () => unawaited(api.sendFile(device)),
            icon: const Icon(Icons.upload_file_rounded),
            label: const Text('发送文件'),
          ),
        ],
      ),
    );
  }
}

class _MacPairingSheetOverlay extends StatefulWidget {
  const _MacPairingSheetOverlay({
    required this.api,
    required this.snapshot,
    required this.onClose,
  });

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;
  final VoidCallback onClose;

  @override
  State<_MacPairingSheetOverlay> createState() =>
      _MacPairingSheetOverlayState();
}

class _MacPairingSheetOverlayState extends State<_MacPairingSheetOverlay> {
  String _pin = '';

  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: Material(
        color: Colors.black.withValues(alpha: 0.18),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints.tightFor(width: 400, height: 350),
            child: _SurfaceCard(color: Colors.white, child: _content(context)),
          ),
        ),
      ),
    );
  }

  Widget _content(BuildContext context) {
    final pinDisplay = widget.snapshot.pinDisplay;
    final pinInputDevice = widget.snapshot.pinInputDevice;
    if (pinDisplay != null) {
      return Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text('配对请求', style: _titleStyle(context)),
          const SizedBox(height: 14),
          Text('${pinDisplay.requestingDevice.deviceName} 请求绑定。'),
          const SizedBox(height: 10),
          const Text('请在对方设备上输入以下配对码：'),
          const SizedBox(height: 16),
          SelectableText(
            pinDisplay.pin,
            style: Theme.of(context).textTheme.displayMedium?.copyWith(
              color: const Color(0xff156f5b),
              fontWeight: FontWeight.w800,
              fontFamily: 'Menlo',
            ),
          ),
          const SizedBox(height: 24),
          OutlinedButton(onPressed: widget.onClose, child: const Text('取消')),
        ],
      );
    }

    if (pinInputDevice != null) {
      return Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text('输入配对码', style: _titleStyle(context)),
          const SizedBox(height: 14),
          Text('请输入 ${pinInputDevice.deviceName} 上显示的 4 位配对码：'),
          const SizedBox(height: 16),
          SizedBox(
            width: 150,
            child: TextField(
              autofocus: true,
              maxLength: 4,
              keyboardType: TextInputType.number,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.w800,
                fontFamily: 'Menlo',
              ),
              decoration: const InputDecoration(
                counterText: '',
                hintText: '配对码',
                border: OutlineInputBorder(),
              ),
              onChanged: (value) => setState(() => _pin = value.trim()),
              onSubmitted: (_) => _verifyPin(pinInputDevice),
            ),
          ),
          const SizedBox(height: 24),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              OutlinedButton(
                onPressed: widget.onClose,
                child: const Text('取消'),
              ),
              const SizedBox(width: 20),
              FilledButton(
                onPressed: _pin.length == 4
                    ? () => _verifyPin(pinInputDevice)
                    : null,
                child: const Text('验证'),
              ),
            ],
          ),
        ],
      );
    }

    return Column(
      children: [
        const SizedBox(height: 10),
        Text(
          widget.snapshot.role == DeviceRole.receiver
              ? '寻找附近的发送端...'
              : '正在广播，请在手机端点击绑定',
          style: _titleStyle(context),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 18),
        Expanded(
          child: widget.snapshot.role == DeviceRole.receiver
              ? _receiverPairingList(context)
              : const Center(
                  child: SizedBox.square(
                    dimension: 32,
                    child: CircularProgressIndicator(strokeWidth: 2.8),
                  ),
                ),
        ),
        OutlinedButton(onPressed: widget.onClose, child: const Text('取消并关闭')),
      ],
    );
  }

  Widget _receiverPairingList(BuildContext context) {
    if (widget.snapshot.onlineDevices.isEmpty) {
      return const Center(
        child: SizedBox.square(
          dimension: 32,
          child: CircularProgressIndicator(strokeWidth: 2.8),
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      itemCount: widget.snapshot.onlineDevices.length,
      separatorBuilder: (_, _) => const SizedBox(height: 12),
      itemBuilder: (context, index) {
        final device = widget.snapshot.onlineDevices[index];
        return _SurfaceCard(
          color: const Color(0xfff4f7f6),
          child: Row(
            children: [
              Icon(
                _deviceIcon(device.deviceType),
                color: const Color(0xff156f5b),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(device.deviceName, style: _mediumStyle(context)),
                    Text(
                      'Hash: ${device.hash} | Role: ${device.role}',
                      style: _captionStyle(context),
                    ),
                  ],
                ),
              ),
              FilledButton(
                onPressed: () => unawaited(widget.api.requestBinding(device)),
                child: const Text('绑定此设备'),
              ),
            ],
          ),
        );
      },
    );
  }

  void _verifyPin(CastDevice device) {
    if (_pin.length == 4) {
      unawaited(widget.api.verifyBinding(device, _pin));
    }
  }
}

class _MacHistoryPage extends StatefulWidget {
  const _MacHistoryPage({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  State<_MacHistoryPage> createState() => _MacHistoryPageState();
}

class _MacHistoryPageState extends State<_MacHistoryPage> {
  String _selectedDeviceHash = 'All';
  bool _messages = true;

  @override
  Widget build(BuildContext context) {
    final messages = _filteredMessages;
    final clipboardItems = widget.snapshot.clipboardItems;
    final contentKey =
        '${_messages ? 'messages' : 'clipboard'}:${_selectedDeviceHash.toUpperCase()}';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(40, 40, 40, 14),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '历史记录',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(
                    value: true,
                    icon: Icon(Icons.notifications_rounded),
                    label: Text('消息'),
                  ),
                  ButtonSegment(
                    value: false,
                    icon: Icon(Icons.content_paste_rounded),
                    label: Text('粘贴板'),
                  ),
                ],
                selected: {_messages},
                showSelectedIcon: false,
                onSelectionChanged: (selection) =>
                    setState(() => _messages = selection.first),
              ),
              const SizedBox(width: 12),
              IconButton(
                tooltip: '刷新',
                onPressed: () => unawaited(widget.api.refresh()),
                icon: const Icon(Icons.refresh_rounded),
              ),
            ],
          ),
        ),
        AnimatedSize(
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInCubic,
            child: _messages
                ? Padding(
                    key: const ValueKey('mac-device-filter'),
                    padding: const EdgeInsets.fromLTRB(40, 0, 40, 18),
                    child: _MacDeviceFilterBar(
                      devices: _deviceFilters,
                      selectedHash: _effectiveSelectedDeviceHash,
                      onSelected: (hash) =>
                          setState(() => _selectedDeviceHash = hash),
                    ),
                  )
                : const SizedBox.shrink(key: ValueKey('mac-device-filter-off')),
          ),
        ),
        Expanded(
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 260),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInCubic,
            transitionBuilder: (child, animation) {
              final offset = Tween<Offset>(
                begin: const Offset(0.018, 0),
                end: Offset.zero,
              ).animate(animation);
              return FadeTransition(
                opacity: animation,
                child: SlideTransition(position: offset, child: child),
              );
            },
            child: KeyedSubtree(
              key: ValueKey(contentKey),
              child: _messages
                  ? _MacHistoryList(
                      emptyText: '暂无消息记录',
                      children: [
                        for (final message in messages)
                          _HistoryMessageCard(
                            api: widget.api,
                            message: message,
                          ),
                      ],
                    )
                  : _MacHistoryList(
                      emptyText: '暂无粘贴板记录',
                      children: [
                        for (final item in clipboardItems)
                          _ClipboardCard(api: widget.api, item: item),
                      ],
                    ),
            ),
          ),
        ),
      ],
    );
  }

  List<HistoryMessage> get _filteredMessages {
    final selected = _effectiveSelectedDeviceHash;
    if (selected == 'All') {
      return widget.snapshot.historyMessages;
    }
    return widget.snapshot.historyMessages
        .where(
          (message) =>
              message.deviceHash.toUpperCase() == selected.toUpperCase(),
        )
        .toList();
  }

  List<_MacDeviceFilter> get _deviceFilters {
    final filters = <_MacDeviceFilter>[
      const _MacDeviceFilter(hash: 'All', name: '全部设备', deviceType: 'All'),
    ];
    final seen = <String>{'ALL'};
    for (final device in widget.snapshot.boundDevices) {
      final hash = device.hash.toUpperCase();
      if (hash.isEmpty || !seen.add(hash)) {
        continue;
      }
      filters.add(
        _MacDeviceFilter(
          hash: device.hash,
          name: device.name,
          deviceType: device.deviceType,
        ),
      );
    }
    for (final message in widget.snapshot.historyMessages) {
      final hash = message.deviceHash.toUpperCase();
      if (hash.isEmpty || !seen.add(hash)) {
        continue;
      }
      filters.add(
        _MacDeviceFilter(
          hash: message.deviceHash,
          name: message.deviceHash,
          deviceType: 'Unknown',
        ),
      );
    }
    return filters;
  }

  String get _effectiveSelectedDeviceHash {
    if (_selectedDeviceHash == 'All') {
      return 'All';
    }
    final stillExists = _deviceFilters.any(
      (filter) =>
          filter.hash.toUpperCase() == _selectedDeviceHash.toUpperCase(),
    );
    return stillExists ? _selectedDeviceHash : 'All';
  }
}

class _MacHistoryList extends StatelessWidget {
  const _MacHistoryList({required this.emptyText, required this.children});

  final String emptyText;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    if (children.isEmpty) {
      return _EmptyText(emptyText);
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(40, 0, 40, 24),
      children: children,
    );
  }
}

class _MacDeviceFilter {
  const _MacDeviceFilter({
    required this.hash,
    required this.name,
    required this.deviceType,
  });

  final String hash;
  final String name;
  final String deviceType;
}

class _MacDeviceFilterBar extends StatelessWidget {
  const _MacDeviceFilterBar({
    required this.devices,
    required this.selectedHash,
    required this.onSelected,
  });

  final List<_MacDeviceFilter> devices;
  final String selectedHash;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (final device in devices) ...[
            _MacDeviceFilterChip(
              device: device,
              selected: device.hash.toUpperCase() == selectedHash.toUpperCase(),
              onTap: () => onSelected(device.hash),
            ),
            const SizedBox(width: 8),
          ],
        ],
      ),
    );
  }
}

class _MacDeviceFilterChip extends StatelessWidget {
  const _MacDeviceFilterChip({
    required this.device,
    required this.selected,
    required this.onTap,
  });

  final _MacDeviceFilter device;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: device.hash == 'All' ? '全部设备' : device.hash,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(18),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            height: 36,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: selected ? const Color(0xff156f5b) : Colors.white,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(
                color: selected
                    ? const Color(0xff156f5b)
                    : const Color(0x1f26332f),
              ),
              boxShadow: selected
                  ? const [
                      BoxShadow(
                        color: Color(0x1f156f5b),
                        blurRadius: 16,
                        offset: Offset(0, 8),
                      ),
                    ]
                  : null,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  device.hash == 'All'
                      ? Icons.all_inclusive_rounded
                      : _deviceIcon(device.deviceType),
                  size: 17,
                  color: selected ? Colors.white : const Color(0xff53605a),
                ),
                const SizedBox(width: 7),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 180),
                  child: Text(
                    device.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: _mediumStyle(context).copyWith(
                      color: selected ? Colors.white : const Color(0xff26332f),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MacUpdatesPage extends StatelessWidget {
  const _MacUpdatesPage({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '自动更新',
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            FilledButton.icon(
              onPressed: () => unawaited(api.checkUpdate()),
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('检查更新'),
            ),
          ],
        ),
        const SizedBox(height: 14),
        _SurfaceCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '当前版本 ${snapshot.update.currentVersion}',
                style: _titleStyle(context),
              ),
              const SizedBox(height: 8),
              if (snapshot.update.latestRelease == null)
                Text(
                  snapshot.update.message.isBlank
                      ? '当前没有可用更新。'
                      : snapshot.update.message,
                  style: _subtleStyle(context),
                )
              else
                _ReleaseCard(
                  api: api,
                  title: '发现新版本 ${snapshot.update.latestRelease!.versionName}',
                  release: snapshot.update.latestRelease!,
                  downloadLabel: '下载 macOS 安装包',
                  redownloadLabel: '重新下载 macOS 安装包',
                ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(child: Text('历史更新', style: _titleStyle(context))),
            TextButton(
              onPressed: () => unawaited(api.refreshUpdateHistory()),
              child: const Text('刷新'),
            ),
          ],
        ),
        for (final release in snapshot.update.historyReleases) ...[
          _ReleaseCard(
            api: api,
            title: 'CastPigeon macOS ${release.versionName}',
            release: release,
            downloadLabel: '下载 macOS 安装包',
            redownloadLabel: '重新下载 macOS 安装包',
          ),
          const SizedBox(height: 8),
        ],
      ],
    );
  }
}

class _SnapshotDialogs extends StatelessWidget {
  const _SnapshotDialogs({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        if (snapshot.phase == ConnectionPhase.pairingRequest &&
            snapshot.pairingDeviceName != null)
          _DeferredDialog(
            key: ValueKey('ble-pairing-${snapshot.pairingDeviceName}'),
            child: _BlePairingRequestDialog(
              api: api,
              pairingDeviceName: snapshot.pairingDeviceName!,
            ),
          ),
        if (snapshot.pinDisplay != null)
          _DeferredDialog(
            key: ValueKey(
              'pin-display-${snapshot.pinDisplay!.requestingDevice.hash}-${snapshot.pinDisplay!.pin}',
            ),
            child: _PinDisplayDialog(
              api: api,
              pinDisplay: snapshot.pinDisplay!,
            ),
          ),
        if (snapshot.pinInputDevice != null)
          _DeferredDialog(
            key: ValueKey('pin-input-${snapshot.pinInputDevice!.hash}'),
            child: _PinInputDialog(api: api, device: snapshot.pinInputDevice!),
          ),
      ],
    );
  }
}

class _DashboardPage extends StatelessWidget {
  const _DashboardPage({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 112),
      children: [
        _StatusCard(api: api, snapshot: snapshot),
        if (snapshot.transferStatus != null) ...[
          const SizedBox(height: 10),
          _TransferCard(status: snapshot.transferStatus!),
        ],
        if (snapshot.workMode == WorkMode.pairing ||
            snapshot.onlineDevices.isNotEmpty) ...[
          const SizedBox(height: 16),
          _SectionHeader(
            title: '在线设备',
            trailing:
                snapshot.workMode == WorkMode.pairing &&
                    snapshot.onlineDevices.isEmpty
                ? '搜索中'
                : null,
          ),
          const SizedBox(height: 8),
          if (snapshot.onlineDevices.isEmpty)
            _LoadingDeviceCard()
          else
            for (final device in snapshot.onlineDevices) ...[
              _OnlineDeviceCard(api: api, snapshot: snapshot, device: device),
              const SizedBox(height: 8),
            ],
        ],
        const SizedBox(height: 10),
        _BoundDevicesCard(api: api, snapshot: snapshot),
        const SizedBox(height: 10),
        _AdvancedLabCard(api: api, snapshot: snapshot),
        if (snapshot.phase == ConnectionPhase.transferring) ...[
          const SizedBox(height: 10),
          _MessageTestCard(api: api, snapshot: snapshot),
        ],
      ],
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final active = snapshot.workMode != WorkMode.idle;
    final statusColor = switch (snapshot.phase) {
      ConnectionPhase.idle => Colors.grey,
      ConnectionPhase.transferring => const Color(0xff4caf50),
      _ => const Color(0xffffb300),
    };
    return _GlassCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 9,
                height: 9,
                decoration: BoxDecoration(
                  color: statusColor,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      snapshot.connectionStateLabel,
                      style: _titleStyle(context),
                    ),
                    Text(snapshot.workModeLabel, style: _subtleStyle(context)),
                  ],
                ),
              ),
              Switch(
                value: active,
                onChanged: (checked) {
                  if (checked) {
                    final action = snapshot.boundDevices.isEmpty
                        ? api.startPairing()
                        : api.startWorking();
                    unawaited(action);
                  } else {
                    unawaited(api.stop());
                  }
                },
              ),
            ],
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            children: [
              ChoiceChip(
                selected: snapshot.role == DeviceRole.sender,
                label: const Text('发送端'),
                onSelected: snapshot.workMode == WorkMode.idle
                    ? (_) => unawaited(api.setRole(DeviceRole.sender))
                    : null,
              ),
              ChoiceChip(
                selected: snapshot.role == DeviceRole.receiver,
                label: const Text('接收端'),
                onSelected: snapshot.workMode == WorkMode.idle
                    ? (_) => unawaited(api.setRole(DeviceRole.receiver))
                    : null,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _TransferCard extends StatelessWidget {
  const _TransferCard({required this.status});

  final TransferStatus status;

  @override
  Widget build(BuildContext context) {
    final sending = status.direction == 'Sending';
    final title = switch (status.phase) {
      'InProgress' => sending ? '正在发送文件' : '正在接收文件',
      'Success' => sending ? '发送成功' : '接收成功',
      'Failed' => sending ? '发送失败' : '接收失败',
      _ => '文件传输',
    };
    return _SurfaceCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: _titleStyle(context)),
          const SizedBox(height: 4),
          Text(status.fileName, maxLines: 1, overflow: TextOverflow.ellipsis),
          Text(status.peerLabel, style: _subtleStyle(context)),
          if (status.phase == 'InProgress') ...[
            const SizedBox(height: 8),
            LinearProgressIndicator(value: status.progressFraction),
            const SizedBox(height: 4),
            Text(_progressLabel(status), style: _subtleStyle(context)),
          ] else if ((status.detail ?? '').isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(status.detail!, style: _subtleStyle(context)),
          ],
        ],
      ),
    );
  }
}

class _OnlineDeviceCard extends StatelessWidget {
  const _OnlineDeviceCard({
    required this.api,
    required this.snapshot,
    required this.device,
  });

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;
  final CastDevice device;

  @override
  Widget build(BuildContext context) {
    final bound = snapshot.boundDevices.any(
      (entry) => entry.hash.toUpperCase() == device.hash.toUpperCase(),
    );
    final notificationEnabled =
        snapshot.boundDevices
            .where(
              (entry) => entry.hash.toUpperCase() == device.hash.toUpperCase(),
            )
            .map((entry) => entry.notificationSharingEnabled)
            .firstOrNull ??
        bound;
    return _SurfaceCard(
      onTap: snapshot.workMode == WorkMode.pairing
          ? () => unawaited(api.requestBinding(device))
          : null,
      child: Row(
        children: [
          Icon(_deviceIcon(device.deviceType), color: const Color(0xff156f5b)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  device.deviceName,
                  style: _titleStyle(context),
                  maxLines: 1,
                ),
                const SizedBox(height: 2),
                Text(
                  '${device.deviceType} / ${bound ? "已绑定" : "组内设备"} / ${device.lanReachable ? "局域网可达" : "等待验证"} / ${device.ipAddress} / 端口 ${device.filePort ?? "不可用"}',
                  style: _subtleStyle(context),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('通知', style: _captionStyle(context)),
                  Switch(
                    value: notificationEnabled,
                    onChanged: (checked) => unawaited(
                      api.setNotificationSharing(device.hash, checked),
                    ),
                  ),
                ],
              ),
              if (device.filePort != null && device.lanReachable)
                TextButton(
                  onPressed: () => unawaited(api.sendFile(device)),
                  child: const Text('发送文件'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _BoundDevicesCard extends StatelessWidget {
  const _BoundDevicesCard({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return _SurfaceCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text('已绑定设备', style: _titleStyle(context))),
              TextButton(
                onPressed: snapshot.workMode == WorkMode.pairing
                    ? null
                    : () => unawaited(api.startPairing()),
                child: Text(
                  snapshot.workMode == WorkMode.pairing ? '配对中' : '绑定新设备',
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (snapshot.boundDevices.isEmpty)
            Text('还没有绑定设备，可以先搜索附近设备完成配对。', style: _subtleStyle(context))
          else
            for (final entry in snapshot.boundDevices)
              _BoundDeviceRow(api: api, entry: entry),
        ],
      ),
    );
  }
}

class _BoundDeviceRow extends StatelessWidget {
  const _BoundDeviceRow({required this.api, required this.entry});

  final CastPigeonApi api;
  final BoundDevice entry;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(entry.name, style: _mediumStyle(context), maxLines: 1),
                  Text(
                    [
                      'Hash: ${entry.hash}',
                      entry.deviceType == 'Unknown' ? null : entry.deviceType,
                      entry.lastIp,
                    ].whereType<String>().join(' / '),
                    style: _subtleStyle(context),
                    maxLines: 2,
                  ),
                ],
              ),
            ),
            Text('通知', style: _captionStyle(context)),
            Switch(
              value: entry.notificationSharingEnabled,
              onChanged: (checked) =>
                  unawaited(api.setNotificationSharing(entry.hash, checked)),
            ),
            IconButton(
              tooltip: '删除绑定设备',
              onPressed: () => unawaited(api.removeBoundDevice(entry.hash)),
              icon: const Icon(Icons.delete_rounded, color: Color(0xffb3261e)),
            ),
          ],
        ),
        const Divider(height: 12),
      ],
    );
  }
}

class _AdvancedLabCard extends StatelessWidget {
  const _AdvancedLabCard({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final selectedPrivilegeMode = snapshot.privilege.mode == 'Shizuku'
        ? 'Shizuku'
        : 'Default';
    return _SurfaceCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('高级实验室', style: _sectionStyle(context)),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text('真·后台剪贴板', style: _titleStyle(context)),
                        if (snapshot.privilege.isPrivileged) ...[
                          const SizedBox(width: 8),
                          const Icon(
                            Icons.check_circle_rounded,
                            color: Color(0xff4caf50),
                            size: 16,
                          ),
                        ],
                      ],
                    ),
                    Text(
                      _privilegeStatus(snapshot.privilege),
                      style: _subtleStyle(context),
                    ),
                    Text(
                      '当前实际后端：${snapshot.privilege.activeBackend}',
                      style: _captionStyle(context),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          LayoutBuilder(
            builder: (context, constraints) {
              final optionWidth = constraints.maxWidth >= 420
                  ? (constraints.maxWidth - 10) / 2
                  : constraints.maxWidth;
              return Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  SizedBox(
                    width: optionWidth,
                    child: _PrivilegeModeOption(
                      selected: selectedPrivilegeMode == 'Default',
                      title: '默认模式',
                      subtitle: '使用系统常规权限，关闭后台提权。',
                      icon: Icons.security_rounded,
                      onTap: selectedPrivilegeMode == 'Default'
                          ? null
                          : () => unawaited(api.selectPrivilegeMode('Default')),
                    ),
                  ),
                  SizedBox(
                    width: optionWidth,
                    child: _PrivilegeModeOption(
                      selected: selectedPrivilegeMode == 'Shizuku',
                      title: 'Shizuku',
                      subtitle: '启用后台剪贴板读写与系统能力桥接。',
                      icon: Icons.bolt_rounded,
                      onTap: selectedPrivilegeMode == 'Shizuku'
                          ? null
                          : () => unawaited(api.selectPrivilegeMode('Shizuku')),
                    ),
                  ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}

class _PrivilegeModeOption extends StatelessWidget {
  const _PrivilegeModeOption({
    required this.selected,
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.onTap,
  });

  final bool selected;
  final String title;
  final String subtitle;
  final IconData icon;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? const Color(0xff156f5b) : const Color(0xff52605a);
    return Material(
      color: selected ? const Color(0xffdceee8) : Colors.white,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          constraints: const BoxConstraints(minHeight: 92),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: selected
                  ? const Color(0xff156f5b)
                  : const Color(0x1826332f),
              width: selected ? 1.4 : 1,
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: color, size: 22),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(title, style: _mediumStyle(context)),
                        ),
                        if (selected)
                          const Icon(
                            Icons.check_circle_rounded,
                            color: Color(0xff156f5b),
                            size: 18,
                          ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(subtitle, style: _subtleStyle(context)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MessageTestCard extends StatelessWidget {
  const _MessageTestCard({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return _SurfaceCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (snapshot.role == DeviceRole.sender)
            FilledButton(
              onPressed: () => unawaited(api.sendTestNotification()),
              child: const Text('发送测试通知'),
            )
          else ...[
            Text('最新收到消息：', style: _titleStyle(context)),
            const SizedBox(height: 8),
            Text(snapshot.latestReceivedMessage ?? '暂无'),
          ],
        ],
      ),
    );
  }
}

class _BlePairingRequestDialog extends StatelessWidget {
  const _BlePairingRequestDialog({
    required this.api,
    required this.pairingDeviceName,
  });

  final CastPigeonApi api;
  final String pairingDeviceName;

  @override
  Widget build(BuildContext context) {
    final deviceName = pairingDeviceName.split('|').first;
    return AlertDialog(
      title: const Text('配对请求'),
      content: Text('收到来自 [$deviceName] 的请求，是否允许并绑定该设备？'),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
            unawaited(api.rejectPairingRequest());
          },
          child: const Text('拒绝'),
        ),
        FilledButton(
          onPressed: () {
            Navigator.of(context).pop();
            unawaited(api.approvePairingRequest());
          },
          child: const Text('允许并绑定'),
        ),
      ],
    );
  }
}

class _HistoryPage extends StatefulWidget {
  const _HistoryPage({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  State<_HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<_HistoryPage> {
  bool _messages = true;

  @override
  Widget build(BuildContext context) {
    final messages = widget.snapshot.historyMessages;
    final clipboardItems = widget.snapshot.clipboardItems;
    return Column(
      children: [
        _PageTitle(title: '发送历史记录'),
        SegmentedButton<bool>(
          segments: const [
            ButtonSegment(value: true, label: Text('消息')),
            ButtonSegment(value: false, label: Text('粘贴板')),
          ],
          selected: {_messages},
          onSelectionChanged: (selection) =>
              setState(() => _messages = selection.first),
        ),
        const Divider(height: 1),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 112),
            children: [
              if (_messages && messages.isEmpty) const _EmptyText('暂无发送记录'),
              if (!_messages && clipboardItems.isEmpty)
                const _EmptyText('暂无粘贴板记录'),
              if (_messages)
                for (final message in messages)
                  _HistoryMessageCard(api: widget.api, message: message)
              else
                for (final item in clipboardItems)
                  _ClipboardCard(api: widget.api, item: item),
            ],
          ),
        ),
      ],
    );
  }
}

class _SettingsPage extends StatefulWidget {
  const _SettingsPage({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  State<_SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<_SettingsPage> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final query = _query.trim().toLowerCase();
    final apps = widget.snapshot.installedApps.where((app) {
      if (query.isEmpty) return true;
      return app.appName.toLowerCase().contains(query) ||
          app.packageName.toLowerCase().contains(query);
    }).toList();
    return Column(
      children: [
        _PageTitle(title: '控制台'),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Align(
            alignment: Alignment.centerLeft,
            child: Text('应用同步设置', style: _sectionStyle(context)),
          ),
        ),
        SwitchListTile(
          title: const Text('显示系统应用'),
          subtitle: const Text('默认隐藏系统应用，打开后显示完整应用列表'),
          value: widget.snapshot.showSystemApps,
          onChanged: (show) => unawaited(widget.api.setShowSystemApps(show)),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: TextField(
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.search_rounded),
              suffixIcon: _query.isEmpty
                  ? null
                  : IconButton(
                      tooltip: '清空搜索',
                      onPressed: () => setState(() => _query = ''),
                      icon: const Icon(Icons.close_rounded),
                    ),
              hintText: '搜索应用名称或包名',
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
              ),
            ),
            onChanged: (value) => setState(() => _query = value),
          ),
        ),
        const SizedBox(height: 12),
        const Divider(height: 1),
        Expanded(
          child: apps.isEmpty
              ? const _EmptyText('没有找到匹配的应用')
              : ListView.separated(
                  padding: const EdgeInsets.only(bottom: 112),
                  itemCount: apps.length,
                  separatorBuilder: (_, _) =>
                      const Divider(height: 1, indent: 24, endIndent: 24),
                  itemBuilder: (context, index) {
                    final app = apps[index];
                    return ListTile(
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 24,
                        vertical: 6,
                      ),
                      leading: _AppIcon(
                        api: widget.api,
                        packageName: app.packageName,
                        appName: app.appName,
                      ),
                      title: Text(
                        app.appName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        app.packageName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      trailing: Switch(
                        value: app.isSelected,
                        onChanged: (checked) => unawaited(
                          widget.api.setAppSyncEnabled(
                            app.packageName,
                            checked,
                          ),
                        ),
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class _InfoPage extends StatelessWidget {
  const _InfoPage({required this.api, required this.snapshot});

  final CastPigeonApi api;
  final CastPigeonSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 112),
      children: [
        Text(
          '信息',
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 12),
        _SurfaceCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.info_rounded, color: Color(0xff156f5b)),
                  const SizedBox(width: 10),
                  Text('CastPigeon', style: _titleStyle(context)),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '面向 Android 与 macOS 的近场同步工具，用蓝牙与局域网通道完成设备绑定、通知同步、剪贴板同步和文件传输。',
                style: _subtleStyle(context),
              ),
              const SizedBox(height: 8),
              Text(
                '当前版本 ${snapshot.update.currentVersion}',
                style: _subtleStyle(context),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _SurfaceCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('自动更新', style: _titleStyle(context)),
                        Text(
                          '从 GitHub Release 检查 Android 安装包和更新日志',
                          style: _subtleStyle(context),
                        ),
                      ],
                    ),
                  ),
                  TextButton(
                    onPressed: () => unawaited(api.checkUpdate()),
                    child: const Text('检查更新'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              if (snapshot.update.latestRelease == null)
                Text(snapshot.update.message, style: _subtleStyle(context))
              else
                _ReleaseCard(
                  api: api,
                  title: '发现新版本 ${snapshot.update.latestRelease!.versionName}',
                  release: snapshot.update.latestRelease!,
                ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(child: Text('历史更新', style: _titleStyle(context))),
            TextButton(
              onPressed: () => unawaited(api.refreshUpdateHistory()),
              child: const Text('刷新'),
            ),
          ],
        ),
        for (final release in snapshot.update.historyReleases) ...[
          _ReleaseCard(
            api: api,
            title: 'CastPigeon Android ${release.versionName}',
            release: release,
          ),
          const SizedBox(height: 8),
        ],
      ],
    );
  }
}

class _ReleaseCard extends StatelessWidget {
  const _ReleaseCard({
    required this.api,
    required this.title,
    required this.release,
    this.downloadLabel = '下载 APK',
    this.redownloadLabel = '重新下载 APK',
  });

  final CastPigeonApi api;
  final String title;
  final ReleaseInfo release;
  final String downloadLabel;
  final String redownloadLabel;

  @override
  Widget build(BuildContext context) {
    final download = release.download;
    return _SurfaceCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: _mediumStyle(
              context,
            ).copyWith(color: const Color(0xff156f5b)),
          ),
          const SizedBox(height: 6),
          Text(release.assetName, style: _subtleStyle(context)),
          const SizedBox(height: 8),
          _ReleaseMarkdownBody(body: release.body),
          if (download != null &&
              (download.progress >= 0 && download.progress < 100 ||
                  download.isVerifying)) ...[
            const SizedBox(height: 10),
            LinearProgressIndicator(
              value: download.progress < 0
                  ? null
                  : download.progress.clamp(0, 100) / 100,
            ),
            const SizedBox(height: 4),
            Text(
              download.isVerifying
                  ? '正在校验安装包...'
                  : '${download.progress.clamp(0, 100)}%',
              style: _subtleStyle(context),
            ),
          ],
          if (download?.message != null) ...[
            const SizedBox(height: 8),
            Text(
              download!.message!,
              style: _subtleStyle(context).copyWith(
                color: download.isVerified
                    ? const Color(0xff156f5b)
                    : Theme.of(context).colorScheme.error,
              ),
            ),
          ],
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            children: [
              FilledButton(
                onPressed:
                    download != null &&
                        (download.progress >= 0 && download.progress < 100 ||
                            download.isVerifying)
                    ? null
                    : () => unawaited(api.downloadRelease(release.tagName)),
                child: Text(
                  download?.progress == 100 ? redownloadLabel : downloadLabel,
                ),
              ),
              if (download?.isVerified == true)
                OutlinedButton(
                  onPressed: () =>
                      unawaited(api.installRelease(release.tagName)),
                  child: const Text('安装'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ReleaseMarkdownBody extends StatelessWidget {
  const _ReleaseMarkdownBody({required this.body});

  final String body;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final baseText = theme.textTheme.bodyMedium!.copyWith(
      color: const Color(0xff26332f),
      height: 1.42,
    );
    final headingText = theme.textTheme.titleSmall!.copyWith(
      fontWeight: FontWeight.w800,
      color: const Color(0xff26332f),
    );
    return MarkdownBody(
      data: body.isBlank ? '暂无更新日志' : body,
      selectable: true,
      styleSheet: MarkdownStyleSheet.fromTheme(theme).copyWith(
        p: baseText,
        pPadding: const EdgeInsets.only(bottom: 4),
        h1: headingText,
        h1Padding: const EdgeInsets.only(top: 6, bottom: 4),
        h2: headingText,
        h2Padding: const EdgeInsets.only(top: 6, bottom: 4),
        h3: headingText,
        h3Padding: const EdgeInsets.only(top: 6, bottom: 4),
        strong: baseText.copyWith(fontWeight: FontWeight.w800),
        em: baseText.copyWith(fontStyle: FontStyle.italic),
        listBullet: baseText,
        listIndent: 22,
        blockSpacing: 6,
        code: theme.textTheme.bodySmall!.copyWith(
          fontFamily: 'Menlo',
          color: const Color(0xff0f3f35),
          backgroundColor: Colors.white.withValues(alpha: 0.72),
        ),
        codeblockPadding: const EdgeInsets.all(10),
        codeblockDecoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.72),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0x1226332f)),
        ),
        blockquote: baseText.copyWith(color: const Color(0xff53605a)),
        blockquotePadding: const EdgeInsets.fromLTRB(12, 8, 10, 8),
        blockquoteDecoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.55),
          borderRadius: BorderRadius.circular(8),
          border: const Border(
            left: BorderSide(color: Color(0xff156f5b), width: 3),
          ),
        ),
        horizontalRuleDecoration: const BoxDecoration(
          border: Border(top: BorderSide(color: Color(0x1a26332f))),
        ),
      ),
    );
  }
}

class _HistoryMessageCard extends StatelessWidget {
  const _HistoryMessageCard({required this.api, required this.message});

  final CastPigeonApi api;
  final HistoryMessage message;

  @override
  Widget build(BuildContext context) {
    return _SurfaceCard(
      margin: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _AppIcon(
            api: api,
            packageName: null,
            appName: message.appName,
            useHistoryCache: true,
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        message.appName,
                        style: _mediumStyle(context),
                      ),
                    ),
                    Text(
                      _formatTimestamp(message.timestamp),
                      style: _captionStyle(context),
                    ),
                  ],
                ),
                if (message.title.isNotEmpty)
                  Text(
                    message.title,
                    style: _mediumStyle(context),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                if (message.content.isNotEmpty)
                  Text(
                    message.content,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ClipboardCard extends StatelessWidget {
  const _ClipboardCard({required this.api, required this.item});

  final CastPigeonApi api;
  final ClipboardHistoryItem item;

  @override
  Widget build(BuildContext context) {
    final direction = switch (item.direction) {
      'sent_to_mac' => '发送到 Mac',
      'received_from_mac' => '来自 Mac',
      'sent_to_android' => '发送到手机',
      'received_from_android' => '来自手机',
      _ => '粘贴板',
    };
    return _SurfaceCard(
      margin: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        direction,
                        style: _mediumStyle(
                          context,
                        ).copyWith(color: const Color(0xff156f5b)),
                      ),
                    ),
                    Text(
                      _formatTimestamp(item.timestamp),
                      style: _captionStyle(context),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  item.content,
                  maxLines: 5,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: '复制粘贴板内容',
            onPressed: () => unawaited(api.copyClipboardHistory(item.content)),
            icon: const Icon(Icons.content_copy_rounded),
          ),
        ],
      ),
    );
  }
}

class _PinDisplayDialog extends StatelessWidget {
  const _PinDisplayDialog({required this.api, required this.pinDisplay});

  final CastPigeonApi api;
  final PinDisplay pinDisplay;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('配对请求'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${pinDisplay.requestingDevice.deviceName} 请求绑定。'),
          const SizedBox(height: 8),
          const Text('请在对方设备上输入以下配对码：'),
          const SizedBox(height: 16),
          Text(
            pinDisplay.pin,
            style: Theme.of(context).textTheme.displaySmall?.copyWith(
              color: const Color(0xff156f5b),
              fontWeight: FontWeight.w800,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
            unawaited(api.cancelPairingPrompt());
          },
          child: const Text('取消'),
        ),
      ],
    );
  }
}

class _PinInputDialog extends StatefulWidget {
  const _PinInputDialog({required this.api, required this.device});

  final CastPigeonApi api;
  final CastDevice device;

  @override
  State<_PinInputDialog> createState() => _PinInputDialogState();
}

class _PinInputDialogState extends State<_PinInputDialog> {
  String _pin = '';

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('输入配对码'),
      content: TextField(
        maxLength: 4,
        keyboardType: TextInputType.number,
        decoration: InputDecoration(
          labelText: '请输入 ${widget.device.deviceName} 上显示的 4 位配对码',
        ),
        onChanged: (value) => setState(() => _pin = value),
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
            unawaited(widget.api.cancelPairingPrompt());
          },
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _pin.length == 4
              ? () {
                  Navigator.of(context).pop();
                  unawaited(widget.api.verifyBinding(widget.device, _pin));
                }
              : null,
          child: const Text('验证'),
        ),
      ],
    );
  }
}

class _DeferredDialog extends StatefulWidget {
  const _DeferredDialog({super.key, required this.child});

  final Widget child;

  @override
  State<_DeferredDialog> createState() => _DeferredDialogState();
}

class _DeferredDialogState extends State<_DeferredDialog> {
  bool _shown = false;
  NavigatorState? _navigator;
  DialogRoute<void>? _route;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _navigator ??= Navigator.of(context, rootNavigator: true);
    if (_shown) return;
    _shown = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _route != null) return;
      final navigator = _navigator;
      if (navigator == null) return;
      final route = DialogRoute<void>(
        context: context,
        builder: (_) => widget.child,
      );
      _route = route;
      unawaited(
        navigator.push<void>(route).whenComplete(() {
          _route = null;
        }),
      );
    });
  }

  @override
  void dispose() {
    final route = _route;
    if (route != null && route.isActive) {
      _navigator?.removeRoute(route);
    }
    _route = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}

class _GlassCard extends StatelessWidget {
  const _GlassCard({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.72),
        border: Border.all(color: const Color(0x1426332f)),
        borderRadius: BorderRadius.circular(8),
        boxShadow: const [
          BoxShadow(
            color: Color(0x120e2019),
            blurRadius: 22,
            offset: Offset(0, 14),
          ),
        ],
      ),
      child: child,
    );
  }
}

class _SurfaceCard extends StatelessWidget {
  const _SurfaceCard({
    required this.child,
    this.onTap,
    this.margin,
    this.color,
    this.borderColor,
  });

  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsetsGeometry? margin;
  final Color? color;
  final Color? borderColor;

  @override
  Widget build(BuildContext context) {
    final card = Container(
      margin: margin,
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: color ?? const Color(0xffeef3f1),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: borderColor ?? const Color(0x1226332f)),
      ),
      child: child,
    );
    if (onTap == null) return card;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: card,
    );
  }
}

class _LoadingDeviceCard extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return _SurfaceCard(
      child: Row(
        children: [
          const SizedBox.square(
            dimension: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          const SizedBox(width: 12),
          Text('正在发现附近设备...', style: _subtleStyle(context)),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title, this.trailing});

  final String title;
  final String? trailing;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: Text(title, style: _titleStyle(context))),
        if (trailing != null) Text(trailing!, style: _sectionStyle(context)),
      ],
    );
  }
}

class _PageTitle extends StatelessWidget {
  const _PageTitle({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: Theme.of(context).colorScheme.surface,
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
      child: Text(
        title,
        style: Theme.of(
          context,
        ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
      ),
    );
  }
}

class _EmptyText extends StatelessWidget {
  const _EmptyText(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 360,
      child: Center(child: Text(text, style: _subtleStyle(context))),
    );
  }
}

class _AppIcon extends StatefulWidget {
  const _AppIcon({
    required this.api,
    required this.packageName,
    required this.appName,
    this.useHistoryCache = false,
  });

  final CastPigeonApi? api;
  final String? packageName;
  final String appName;
  final bool useHistoryCache;

  @override
  State<_AppIcon> createState() => _AppIconState();
}

class _AppIconState extends State<_AppIcon> {
  String? _base64Icon;
  Object? _loadKey;

  @override
  void initState() {
    super.initState();
    _loadIcon();
  }

  @override
  void didUpdateWidget(covariant _AppIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    final key = _currentKey;
    if (key != _loadKey) {
      _base64Icon = null;
      _loadIcon();
    }
  }

  @override
  Widget build(BuildContext context) {
    final image = _base64Icon == null
        ? null
        : Image.memory(
            base64Decode(_base64Icon!),
            width: 44,
            height: 44,
            fit: BoxFit.cover,
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => _fallback(),
          );
    return Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        color: const Color(0xffdfe9e5),
        borderRadius: BorderRadius.circular(8),
      ),
      clipBehavior: Clip.antiAlias,
      alignment: Alignment.center,
      child: image ?? _fallback(),
    );
  }

  Object get _currentKey =>
      Object.hash(widget.packageName, widget.appName, widget.useHistoryCache);

  Widget _fallback() {
    return Text(
      widget.appName.isEmpty ? '?' : widget.appName.characters.first,
      style: const TextStyle(fontWeight: FontWeight.w800),
    );
  }

  Future<void> _loadIcon() async {
    final api = widget.api;
    if (api == null) return;
    final key = _currentKey;
    _loadKey = key;
    final icon = widget.useHistoryCache
        ? await api.historyIconBase64(widget.appName)
        : await api.appIconBase64(widget.packageName ?? '');
    if (!mounted || key != _loadKey) return;
    if (icon != null && icon.isNotEmpty) {
      setState(() => _base64Icon = icon);
    }
  }
}

IconData _deviceIcon(String type) {
  return switch (type.toLowerCase()) {
    'android' => Icons.phone_android_rounded,
    'macos' => Icons.laptop_mac_rounded,
    'windows' => Icons.desktop_windows_rounded,
    _ => Icons.devices_other_rounded,
  };
}

String _progressLabel(TransferStatus status) {
  final fraction = status.progressFraction;
  if (fraction == null) return '传输中';
  return '${(math.min(1, math.max(0, fraction)) * 100).toInt()}%';
}

String _formatTimestamp(int timestamp) {
  if (timestamp <= 0) return '';
  final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
  String two(int value) => value.toString().padLeft(2, '0');
  return '${two(date.month)}-${two(date.day)} ${two(date.hour)}:${two(date.minute)}:${two(date.second)}';
}

String _privilegeStatus(PrivilegeState state) {
  if (state.mode == 'Shizuku') {
    return switch (state.bindStatus) {
      'Binding' => '正在连接 Shizuku...',
      'Connected' => 'Shizuku 提权已生效',
      'Failed' => 'Shizuku 授权失败',
      _ => '已选择 Shizuku 模式',
    };
  }
  return '未开启后台提权同步';
}

TextStyle _titleStyle(BuildContext context) {
  return Theme.of(
    context,
  ).textTheme.titleMedium!.copyWith(fontWeight: FontWeight.w800);
}

TextStyle _mediumStyle(BuildContext context) {
  return Theme.of(
    context,
  ).textTheme.bodyMedium!.copyWith(fontWeight: FontWeight.w700);
}

TextStyle _sectionStyle(BuildContext context) {
  return Theme.of(context).textTheme.bodyMedium!.copyWith(
    color: const Color(0xff156f5b),
    fontWeight: FontWeight.w800,
  );
}

TextStyle _subtleStyle(BuildContext context) {
  return Theme.of(
    context,
  ).textTheme.bodySmall!.copyWith(color: const Color(0xff68756f));
}

TextStyle _captionStyle(BuildContext context) {
  return Theme.of(
    context,
  ).textTheme.labelSmall!.copyWith(color: const Color(0xff68756f));
}

extension on String {
  bool get isBlank => trim().isEmpty;
}
