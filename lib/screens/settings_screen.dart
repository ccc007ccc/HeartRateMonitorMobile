// lib/screens/settings_screen.dart

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'package:permission_handler/permission_handler.dart';
import '../services/ble_service.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _isLoading = true;
  bool _autoConnectEnabled = false;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _autoConnectEnabled = prefs.getBool(kAutoConnectKey) ?? false;
      _isLoading = false;
    });
  }

  Future<void> _toggleAutoConnect(bool value) async {
    setState(() {
      _autoConnectEnabled = value;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kAutoConnectKey, value);
  }

  // --- 修复后的权限请求和悬浮窗显示逻辑 ---
  Future<void> _handleOverlayPermission() async {
    try {
      if (await FlutterOverlayWindow.isActive()) {
        _showSnackbar('悬浮窗已在运行中');
        return;
      }

      var status = await Permission.systemAlertWindow.status;
      if (status.isGranted) {
        await _showOverlay();
        return;
      }

      if (status.isDenied) {
        status = await Permission.systemAlertWindow.request();
        if (status.isGranted) {
          await _showOverlay();
        } else {
          _showSnackbar('悬浮窗权限被拒绝');
        }
        return;
      }

      if (status.isPermanentlyDenied) {
        _showPermissionPermanentlyDeniedDialog();
      }
    } catch (e) {
      _showSnackbar('发生错误: $e');
    }
  }

  Future<void> _showOverlay() async {
  try {
    await FlutterOverlayWindow.showOverlay(
      height: 150,
      width: 300,
      alignment: OverlayAlignment.center,
      enableDrag: true,
      overlayTitle: "overlayMain", // 必须和入口函数名匹配
    );
    debugPrint('Overlay launched successfully');
  } catch (e) {
    _showSnackbar('显示悬浮窗失败: $e');
  }
}

  
  // --- 修复后的关闭悬浮窗逻辑 ---
  Future<void> _handleCloseOverlay() async {
    try {
      if(await FlutterOverlayWindow.isActive()) {
        await FlutterOverlayWindow.closeOverlay();
        _showSnackbar('悬浮窗已关闭');
      } else {
        _showSnackbar('悬浮窗当前未运行');
      }
    } catch(e) {
       _showSnackbar('关闭悬浮窗失败: $e');
    }
  }

  // 封装的 SnackBar 方法，内置 mounted 检查
  void _showSnackbar(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  void _showPermissionPermanentlyDeniedDialog() {
    if (!mounted) return;
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('权限已被永久拒绝'),
        content: const Text('请在系统设置中手动为此应用开启“在其他应用上层显示”的权限，才能使用悬浮窗功能。'),
        actions: [
          TextButton(
            child: const Text('取消'),
            onPressed: () => Navigator.of(context).pop(),
          ),
          TextButton(
            child: const Text('去设置'),
            onPressed: () {
              Navigator.of(context).pop();
              openAppSettings(); // 打开应用设置
            },
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: _isLoading 
          ? const Center(child: CircularProgressIndicator()) 
          : _buildSettingsList(),
    );
  }

  Widget _buildSettingsList() {
    return ListView(
      children: [
        _buildSectionTitle(context, '通用'),
        SwitchListTile(
          secondary: const Icon(Icons.bluetooth_searching),
          title: const Text('自动连接到收藏的设备'),
          subtitle: const Text('启动应用时自动连接'),
          value: _autoConnectEnabled,
          onChanged: _toggleAutoConnect,
        ),
        const Divider(),
        _buildSectionTitle(context, '悬浮窗'),
        ListTile(
          leading: const Icon(Icons.picture_in_picture_alt_outlined),
          title: const Text('显示悬浮窗'),
          subtitle: const Text('在屏幕上显示心率小组件'),
          onTap: _handleOverlayPermission,
        ),
        ListTile(
          leading: const Icon(Icons.cancel_outlined, color: Colors.red),
          title: const Text('关闭悬浮窗'),
          onTap: _handleCloseOverlay,
        ),
        const Divider(),
        _buildSectionTitle(context, '关于'),
        const ListTile(
          leading: Icon(Icons.info_outline),
          title: Text('应用版本'),
          subtitle: Text('1.6.1'), // 版本号更新
        ),
      ],
    );
  }

  Widget _buildSectionTitle(BuildContext context, String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16.0, 20.0, 16.0, 8.0),
      child: Text(
        title.toUpperCase(),
        style: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.bold,
          color: Theme.of(context).primaryColor,
        ),
      ),
    );
  }
}