// lib/widgets/overlay_widget.dart

import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

class OverlayWidget extends StatefulWidget {
  const OverlayWidget({super.key});

  @override
  State<OverlayWidget> createState() => _OverlayWidgetState();
}

class _OverlayWidgetState extends State<OverlayWidget> {
  int _heartRate = 0;

  @override
  void initState() {
    super.initState();
    // ✅ 使用 overlayListener 替代不存在的 receiveBroadcast
    FlutterOverlayWindow.overlayListener.listen((data) {
      if (!mounted) return;
      if (data is Map<String, dynamic> && data.containsKey('heartRate')) {
        setState(() {
          _heartRate = data['heartRate'] as int;
        });
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Center(
        child: _buildAcrylicCard(),
      ),
    );
  }

  Widget _buildAcrylicCard() {
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 10.0, sigmaY: 10.0),
        child: Container(
          width: 180,
          height: 90,
          decoration: BoxDecoration(
            color: Colors.white.withAlpha(64), // ≈ 0.25 opacity
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.white.withAlpha(102)), // ≈ 0.4 opacity
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Text(
                '❤️',
                style: TextStyle(
                  fontSize: 32,
                  shadows: [
                    Shadow(blurRadius: 10, color: Colors.black.withAlpha(128)) // ≈ 0.5 opacity
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Text(
                _heartRate > 0 ? '$_heartRate' : '--',
                style: const TextStyle(
                  fontSize: 48,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                  shadows: [
                    Shadow(blurRadius: 10, color: Colors.black)
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
