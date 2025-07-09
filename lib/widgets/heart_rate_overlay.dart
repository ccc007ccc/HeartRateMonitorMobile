// lib/widgets/heart_rate_overlay.dart

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'package:shared_preferences/shared_preferences.dart';

class HeartRateOverlay extends StatefulWidget {
  const HeartRateOverlay({super.key});

  @override
  State<HeartRateOverlay> createState() => _HeartRateOverlayState();
}

class _HeartRateOverlayState extends State<HeartRateOverlay> with TickerProviderStateMixin {
  int _heartRate = 0; // 心率值
  StreamSubscription<dynamic>? _subscription; // 数据监听器

  late AnimationController _animationController; // 动画控制器
  late Animation<double> _scaleAnimation; // 缩放动画

  // 悬浮窗设置的变量
  Color _backgroundColor = Colors.black; // 背景颜色
  Color _fontColor = Colors.white; // 字体颜色
  Color _borderColor = Colors.white; // 边框颜色
  bool _showBorder = true; // 是否显示边框
  bool _animationEnabled = true; // 是否启用动画
  double _scale = 1.0; // 内容缩放比例
  double _opacity = 1.0; // 背景不透明度
  bool _isDisposed = false; // 标记是否已销毁

  @override
  void initState() {
    super.initState();
    _setupAnimation(); // 初始化动画
    _loadInitialSettings(); // 加载初始设置
    _listenForUpdates(); // 监听数据更新
  }

  /// 初始化动画控制器
  void _setupAnimation() {
    _animationController = AnimationController(vsync: this);
    // 定义一个从 1.0 到 1.25 的弹性缩放动画
    _scaleAnimation = Tween<double>(begin: 1.0, end: 1.25).animate(
      CurvedAnimation(parent: _animationController, curve: Curves.elasticOut),
    );
  }

  /// 根据BPM（每分钟心跳次数）更新心跳动画的频率
  void _updateAnimation(int bpm) {
    if (_isDisposed) return; // 如果已销毁则不执行

    if (bpm > 20 && _animationEnabled) {
      // 计算每次心跳的持续时间，并转换为动画控制器的 duration
      final duration = Duration(milliseconds: (30000 / bpm).round()); // 60000ms / bpm / 2
      if (_animationController.duration != duration) {
        _animationController.duration = duration;
      }
      if (!_animationController.isAnimating) {
        _animationController.repeat(reverse: true); // 重复播放动画（放大再缩小）
      }
    } else {
      // 如果BPM过低或动画被禁用，则停止动画
      if (_animationController.isAnimating) {
        _animationController.stop();
        _animationController.value = 0.0; // 重置动画状态
      }
    }
  }

  /// 监听来自主应用或设置页面的数据更新
  void _listenForUpdates() {
    _subscription = FlutterOverlayWindow.overlayListener.listen((data) {
      if (_isDisposed || data is! Map) return; // 安全检查

      int newHeartRate = _heartRate;
      bool newAnimationEnabled = _animationEnabled;
      bool needsUiUpdate = false; // 标记是否需要刷新UI

      // 辅助函数，安全地解析数值
      double? parseNumeric(dynamic value) {
        if (value is double) return value;
        if (value is int) return value.toDouble();
        return null;
      }
      
      // 解析收到的数据，并更新对应的设置变量
      if (data.containsKey('heartRate') && data['heartRate'] is int) {
        newHeartRate = data['heartRate'];
        needsUiUpdate = true;
      }
      if (data.containsKey('overlay_background_color') && data['overlay_background_color'] is int) {
        _backgroundColor = Color(data['overlay_background_color']);
        needsUiUpdate = true;
      }
      if (data.containsKey('overlay_font_color') && data['overlay_font_color'] is int) {
        _fontColor = Color(data['overlay_font_color']);
        needsUiUpdate = true;
      }
      if (data.containsKey('overlay_border_color') && data['overlay_border_color'] is int) {
        _borderColor = Color(data['overlay_border_color']);
        needsUiUpdate = true;
      }
      if (data.containsKey('show_overlay_border') && data['show_overlay_border'] is bool) {
        _showBorder = data['show_overlay_border'];
        needsUiUpdate = true;
      }
      if (data.containsKey('heartbeat_animation_enabled') && data['heartbeat_animation_enabled'] is bool) {
        newAnimationEnabled = data['heartbeat_animation_enabled'];
        needsUiUpdate = true;
      }

      // 注意：这里的 'overlay_scale' 仅用于调整内部内容的视觉大小
      // 悬浮窗的实际物理尺寸是在设置页面通过 resizeOverlay API 调整的
      final newScale = parseNumeric(data['overlay_scale']);
      if (newScale != null) {
        _scale = newScale;
        needsUiUpdate = true;
      }

      final newOpacity = parseNumeric(data['overlay_opacity']);
      if (newOpacity != null) {
        _opacity = newOpacity;
        needsUiUpdate = true;
      }

      // 如果有任何UI相关的更新，则调用 setState
      if (needsUiUpdate) {
        setState(() {
          _heartRate = newHeartRate;
          _animationEnabled = newAnimationEnabled;
        });
      }

      // 根据最新的心率值更新动画
      _updateAnimation(newHeartRate);
    });
  }

  /// 在悬浮窗启动时加载一次初始设置，确保显示正确的样式
  Future<void> _loadInitialSettings() async {
    final prefs = await SharedPreferences.getInstance();
    if (_isDisposed) return;
    try {
      // 从本地存储中读取各项设置
      _backgroundColor = Color(prefs.getInt('overlay_background_color') ?? 0xFF000000);
      _fontColor = Color(prefs.getInt('overlay_font_color') ?? 0xFFFFFFFF);
      _borderColor = Color(prefs.getInt('overlay_border_color') ?? 0xFFFFFFFF);
      _showBorder = prefs.getBool('show_overlay_border') ?? true;
      _animationEnabled = prefs.getBool('heartbeat_animation_enabled') ?? true;
      _scale = prefs.getDouble('overlay_scale') ?? 1.0;
      _opacity = prefs.getDouble('overlay_opacity') ?? 1.0;

      setState(() {}); // 刷新UI以应用加载的设置
      _updateAnimation(_heartRate); // 更新动画状态
    } catch (e) {
      // 忽略加载错误，使用默认值
    }
  }

  @override
  void dispose() {
    _isDisposed = true; // 标记为已销毁
    _animationController.dispose(); // 销毁动画控制器
    _subscription?.cancel(); // 取消数据监听
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // 根Widget使用透明背景
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Center(
        // 使用 Transform.scale 来根据设置缩放内部的所有内容
        child: Transform.scale(
          scale: _scale,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              // 背景色应用不透明度
              color: _backgroundColor.withAlpha((_opacity * 255).round()),
              borderRadius: BorderRadius.circular(16.0),
              // 根据设置决定是否显示边框
              border: _showBorder ? Border.all(color: _borderColor) : null,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min, // Row的宽度由子项决定
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                // 心形图标，应用跳动动画
                ScaleTransition(
                  scale: _scaleAnimation,
                  child: Text('❤️', style: TextStyle(fontSize: 16)),
                ),
                const SizedBox(width: 6),
                // 显示心率数值
                Text(
                  '$_heartRate',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: _fontColor),
                ),
                const SizedBox(width: 3),
                // 显示 'BPM' 单位
                Text(
                  'BPM',
                  style: TextStyle(fontSize: 10, color: _fontColor.withAlpha((255 * 0.7).round())),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}