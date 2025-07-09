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
  int _heartRate = 0;
  StreamSubscription<dynamic>? _subscription;

  late AnimationController _animationController;
  late Animation<double> _scaleAnimation;

  // 悬浮窗设置的变量
  Color _backgroundColor = Colors.black;
  Color _fontColor = Colors.white;
  Color _borderColor = Colors.white;
  bool _showBorder = true;
  bool _animationEnabled = true;
  double _scale = 1.0;
  double _opacity = 1.0;
  bool _isDisposed = false;

  @override
  void initState() {
    super.initState();
    _setupAnimation();
    _loadInitialSettings();
    _listenForUpdates();
  }

  /// 初始化动画控制器
  void _setupAnimation() {
    _animationController = AnimationController(vsync: this);
    _scaleAnimation = Tween<double>(begin: 1.0, end: 1.25).animate(
      CurvedAnimation(parent: _animationController, curve: Curves.elasticOut),
    );
  }

  /// 根据BPM更新心跳动画的频率
  void _updateAnimation(int bpm) {
    if (_isDisposed) return;

    if (bpm > 20 && _animationEnabled) {
      final duration = Duration(milliseconds: (30000 / bpm).round());
      if (_animationController.duration != duration) {
        _animationController.duration = duration;
      }
      if (!_animationController.isAnimating) {
        _animationController.repeat(reverse: true);
      }
    } else {
      if (_animationController.isAnimating) {
        _animationController.stop();
        _animationController.value = 0.0;
      }
    }
  }

  /// 监听来自设置页面的数据更新
  void _listenForUpdates() {
    _subscription = FlutterOverlayWindow.overlayListener.listen((data) {
      if (_isDisposed || data is! Map) return;

      int newHeartRate = _heartRate;
      bool newAnimationEnabled = _animationEnabled;
      bool needsUiUpdate = false;

      double? parseNumeric(dynamic value) {
        if (value is double) return value;
        if (value is int) return value.toDouble();
        return null;
      }

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

      if (needsUiUpdate) {
        setState(() {
          _heartRate = newHeartRate;
          _animationEnabled = newAnimationEnabled;
        });
      }

      _updateAnimation(newHeartRate);
    });
  }

  /// 在悬浮窗启动时加载一次初始设置
  Future<void> _loadInitialSettings() async {
    final prefs = await SharedPreferences.getInstance();
    if (_isDisposed) return;
    try {
      _backgroundColor = Color(prefs.getInt('overlay_background_color') ?? 0xFF000000);
      _fontColor = Color(prefs.getInt('overlay_font_color') ?? 0xFFFFFFFF);
      _borderColor = Color(prefs.getInt('overlay_border_color') ?? 0xFFFFFFFF);
      _showBorder = prefs.getBool('show_overlay_border') ?? true;
      _animationEnabled = prefs.getBool('heartbeat_animation_enabled') ?? true;
      _scale = prefs.getDouble('overlay_scale') ?? 1.0;
      _opacity = prefs.getDouble('overlay_opacity') ?? 1.0;

      setState(() {});
      _updateAnimation(_heartRate);
    } catch (e) {
      // 忽略加载错误
    }
  }

  @override
  void dispose() {
    _isDisposed = true;
    _animationController.dispose();
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Center(
        child: Transform.scale(
          scale: _scale,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: _backgroundColor.withAlpha((_opacity * 255).round()),
              borderRadius: BorderRadius.circular(16.0),
              border: _showBorder ? Border.all(color: _borderColor) : null,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ScaleTransition(
                  scale: _scaleAnimation,
                  child: Text('❤️', style: TextStyle(fontSize: 16)),
                ),
                const SizedBox(width: 6),
                Text(
                  '$_heartRate',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: _fontColor),
                ),
                const SizedBox(width: 3),
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
