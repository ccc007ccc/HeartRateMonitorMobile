// lib/screens/settings_screen.dart

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../services/ble_service.dart'; // 导入 Key

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

  // 异步加载设置值，并更新状态
  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _autoConnectEnabled = prefs.getBool(kAutoConnectKey) ?? false;
      _isLoading = false; // 加载完成后，设置加载状态为 false
    });
  }

  // 切换开关状态时，同步更新UI状态和本地存储
  Future<void> _toggleAutoConnect(bool value) async {
    // 立即更新UI，提供流畅的动画体验
    setState(() {
      _autoConnectEnabled = value;
    });
    // 然后在后台更新本地存储
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kAutoConnectKey, value);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      // 根据加载状态显示不同内容
      body: _isLoading 
          ? const Center(child: CircularProgressIndicator()) 
          : _buildSettingsList(),
    );
  }

  // 将设置列表抽离成一个独立的构建方法
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
        _buildSectionTitle(context, '关于'),
        const ListTile(
          leading: Icon(Icons.info_outline),
          title: Text('应用版本'),
          subtitle: Text('1.4.0'),
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