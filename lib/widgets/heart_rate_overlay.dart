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
    return Material(
      type: MaterialType.transparency,
      child: Container(
        width: double.infinity,
        height: double.infinity,
        color: Colors.transparent,
        child: Center(
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            decoration: BoxDecoration(
              color: Colors.black.withOpacity(0.85),  // 增加不透明度
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: Colors.white.withOpacity(0.3), width: 1), // 添加边框
            ),
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
      ),
    );
  }
}