// lib/screens/settings_screen.dart

import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';
import '../services/ble_service.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _isLoading = true;
  bool _autoConnectEnabled = false;

  // 悬浮窗设置的变量
  Color _overlayBackgroundColor = Colors.black;
  Color _overlayFontColor = Colors.white;
  Color _overlayBorderColor = Colors.white;
  bool _showOverlayBorder = true;
  bool _heartbeatAnimationEnabled = true;
  double _overlayScale = 1.0;
  double _overlayOpacity = 1.0;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  /// 从本地存储加载所有设置项
  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      try {
        _autoConnectEnabled = prefs.getBool(kAutoConnectKey) ?? false;
        // 修复：使用 toARGB32() 替代已弃用的 .value
        _overlayBackgroundColor = Color(prefs.getInt('overlay_background_color') ?? Colors.black.toARGB32());
        _overlayFontColor = Color(prefs.getInt('overlay_font_color') ?? Colors.white.toARGB32());
        _overlayBorderColor = Color(prefs.getInt('overlay_border_color') ?? Colors.white.toARGB32());
        _showOverlayBorder = prefs.getBool('show_overlay_border') ?? true;
        _heartbeatAnimationEnabled = prefs.getBool('heartbeat_animation_enabled') ?? true;
        _overlayScale = prefs.getDouble('overlay_scale') ?? 1.0;
        _overlayOpacity = prefs.getDouble('overlay_opacity') ?? 1.0;
      } catch (e) {
        // 如果加载失败，则使用一套完整的默认值
        _autoConnectEnabled = false;
        _overlayBackgroundColor = Colors.black;
        _overlayFontColor = Colors.white;
        _overlayBorderColor = Colors.white;
        _showOverlayBorder = true;
        _heartbeatAnimationEnabled = true;
        _overlayScale = 1.0;
        _overlayOpacity = 1.0;
      }
      _isLoading = false;
    });
  }

  /// 保存单个设置项到本地，并通知悬浮窗更新
  Future<void> _saveAndApplySetting(String key, dynamic value) async {
    final prefs = await SharedPreferences.getInstance();
    try {
      if (value is bool) await prefs.setBool(key, value);
      if (value is double) await prefs.setDouble(key, value);
      if (value is int) await prefs.setInt(key, value);

      if (await FlutterOverlayWindow.isActive()) {
        await FlutterOverlayWindow.shareData({key: value});
      }
    } catch (e) {
      // 忽略保存错误
    }
  }

  /// 打开颜色选择器对话框
  void _pickColor({
    required BuildContext context,
    required String title,
    required Color initialColor,
    required Function(Color) onColorChosen,
  }) {
    Color pickerColor = initialColor;
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: SingleChildScrollView(
          child: ColorPicker(
            pickerColor: pickerColor,
            onColorChanged: (color) => pickerColor = color,
            pickerAreaHeightPercent: 0.8,
          ),
        ),
        actions: <Widget>[
          TextButton(child: const Text('取消'), onPressed: () => Navigator.of(context).pop()),
          TextButton(
            child: const Text('确定'),
            onPressed: () {
              onColorChosen(pickerColor);
              Navigator.of(context).pop();
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

  /// 构建设置项列表
  Widget _buildSettingsList() {
    return ListView(
      children: [
        _buildSectionTitle('通用'),
        SwitchListTile(
          secondary: const Icon(Icons.bluetooth_searching),
          title: const Text('自动连接到收藏的设备'),
          subtitle: const Text('启动应用时自动连接'),
          value: _autoConnectEnabled,
          onChanged: (value) {
            setState(() => _autoConnectEnabled = value);
            _saveAndApplySetting(kAutoConnectKey, value);
          },
        ),
        const Divider(),
        _buildSectionTitle('悬浮窗设置'),
        SwitchListTile(
          secondary: const Icon(Icons.favorite_border),
          title: const Text('心脏跳动动画'),
          subtitle: const Text('图标根据心率跳动'),
          value: _heartbeatAnimationEnabled,
          onChanged: (value) {
            setState(() => _heartbeatAnimationEnabled = value);
            _saveAndApplySetting('heartbeat_animation_enabled', value);
          },
        ),
        ListTile(
          leading: const Icon(Icons.color_lens_outlined),
          title: const Text('背景颜色'),
          trailing: Container(width: 24, height: 24, decoration: BoxDecoration(color: _overlayBackgroundColor, border: Border.all(color: Colors.grey))),
          onTap: () => _pickColor(context: context, title: '选择背景颜色', initialColor: _overlayBackgroundColor, onColorChosen: (color) {
            setState(() => _overlayBackgroundColor = color);
            _saveAndApplySetting('overlay_background_color', color.toARGB32());
          }),
        ),
        ListTile(
          leading: const Icon(Icons.format_color_text),
          title: const Text('字体颜色'),
          trailing: Container(width: 24, height: 24, decoration: BoxDecoration(color: _overlayFontColor, border: Border.all(color: Colors.grey))),
          onTap: () => _pickColor(context: context, title: '选择字体颜色', initialColor: _overlayFontColor, onColorChosen: (color) {
            setState(() => _overlayFontColor = color);
            _saveAndApplySetting('overlay_font_color', color.toARGB32());
          }),
        ),
        ListTile(
          leading: const Icon(Icons.border_color_outlined),
          title: const Text('边框颜色'),
          trailing: Container(width: 24, height: 24, decoration: BoxDecoration(color: _overlayBorderColor, border: Border.all(color: Colors.grey))),
          onTap: () => _pickColor(context: context, title: '选择边框颜色', initialColor: _overlayBorderColor, onColorChosen: (color) {
            setState(() => _overlayBorderColor = color);
            _saveAndApplySetting('overlay_border_color', color.toARGB32());
          }),
        ),
        SwitchListTile(
          secondary: const Icon(Icons.visibility_outlined),
          title: const Text('显示边框'),
          value: _showOverlayBorder,
          onChanged: (value) {
            setState(() => _showOverlayBorder = value);
            _saveAndApplySetting('show_overlay_border', value);
          },
        ),
        _buildSliderTile(
          icon: Icons.zoom_out_map,
          title: '悬浮窗缩放',
          value: _overlayScale,
          min: 0.5,
          max: 1.2,
          divisions: 7,
          label: _overlayScale.toStringAsFixed(1),
          onChanged: (value) => setState(() => _overlayScale = value),
          onChangeEnd: (value) => _saveAndApplySetting('overlay_scale', value),
        ),
        _buildSliderTile(
          icon: Icons.opacity_outlined,
          title: '背景透明度',
          value: _overlayOpacity,
          min: 0.0,
          max: 1.0,
          divisions: 20,
          label: _overlayOpacity.toStringAsFixed(2),
          onChanged: (value) => setState(() => _overlayOpacity = value),
          onChangeEnd: (value) => _saveAndApplySetting('overlay_opacity', value),
        ),
        const Divider(),
        _buildSectionTitle('关于'),
        const ListTile(
          leading: Icon(Icons.info_outline),
          title: Text('应用版本'),
          subtitle: Text('2.1.1'), // 最终稳定版号
        ),
        ListTile(
          leading: const Icon(Icons.code),
          title: const Text('GitHub仓库'),
          subtitle: const Text('ccc007ccc/HeartRateMonitorMobile'),
          onTap: () => launchUrl(Uri.parse("https://github.com/ccc007ccc/HeartRateMonitorMobile")),
        ),
      ],
    );
  }

  /// 构建一个带滑块的设置项
  Widget _buildSliderTile({ required IconData icon, required String title, required double value, required double min, required double max, required int divisions, required String label, required ValueChanged<double> onChanged, required ValueChanged<double> onChangeEnd, }) {
    return ListTile(
      leading: Icon(icon),
      title: Text(title),
      subtitle: Slider( value: value, min: min, max: max, divisions: divisions, label: label, onChanged: onChanged, onChangeEnd: onChangeEnd, ),
    );
  }

  /// 构建一个区域标题
  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16.0, 20.0, 16.0, 8.0),
      child: Text( title.toUpperCase(), style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: Theme.of(context).primaryColor), ),
    );
  }
}