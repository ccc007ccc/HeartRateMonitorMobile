// lib/widgets/heart_rate_overlay.dart

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

class HeartRateOverlay extends StatefulWidget {
  const HeartRateOverlay({super.key});

  @override
  State<HeartRateOverlay> createState() => _HeartRateOverlayState();
}

class _HeartRateOverlayState extends State<HeartRateOverlay> {
  // 使用一个非零的初始值，方便调试时立刻看到效果
  int _heartRate = 0;
  StreamSubscription<dynamic>? _subscription;

  @override
  void initState() {
    super.initState();
    _subscription = FlutterOverlayWindow.overlayListener.listen((data) {
      if (mounted) {
         if (data is Map<String, dynamic> && data.containsKey('heartRate')) {
          setState(() {
            _heartRate = data['heartRate'];
          });
        }
      }
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // 悬浮窗的根组件必须是 Material，以提供基础的绘制环境。
    return Material(
      color: Colors.transparent,
      // 使用 Center 组件确保内部元素在悬浮窗区域内正确对齐。
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            // 先用一个鲜艳的颜色确保我们能看到悬浮窗
            color: Colors.black.withOpacity(0.75),
            borderRadius: BorderRadius.circular(20),
          ),
          // 先用一个简单的 Text 组件来确保内容可以被绘制
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const Text('❤️', style: TextStyle(fontSize: 20)),
              const SizedBox(width: 8),
              Text(
                '$_heartRate',
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
              ),
              const SizedBox(width: 4),
              const Text(
                'BPM',
                style: TextStyle(fontSize: 12, color: Colors.white70),
              ),
              const SizedBox(width: 8),
              InkWell(
                onTap: () => FlutterOverlayWindow.closeOverlay(),
                child: const Icon(
                  Icons.close,
                  color: Colors.white,
                  size: 18,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}