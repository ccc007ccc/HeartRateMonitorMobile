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
  late Future<bool> _autoConnectFuture;

  @override
  void initState() {
    super.initState();
    _autoConnectFuture = _loadAutoConnectSetting();
  }

  Future<bool> _loadAutoConnectSetting() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(kAutoConnectKey) ?? false;
  }

  Future<void> _toggleAutoConnect(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(kAutoConnectKey, value);
    setState(() {
      _autoConnectFuture = Future.value(value);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: FutureBuilder<bool>(
        future: _autoConnectFuture,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          final autoConnectEnabled = snapshot.data ?? false;

          return ListView(
            children: [
              _buildSectionTitle(context, '通用'),
              SwitchListTile(
                secondary: const Icon(Icons.bluetooth_searching),
                title: const Text('自动连接到收藏的设备'),
                subtitle: const Text('启动应用时自动连接'),
                value: autoConnectEnabled,
                onChanged: _toggleAutoConnect,
              ),
              const Divider(),
              _buildSectionTitle(context, '关于'),
              const ListTile(
                leading: Icon(Icons.info_outline),
                title: Text('应用版本'),
                subtitle: Text('1.3.0'),
              ),
            ],
          );
        },
      ),
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