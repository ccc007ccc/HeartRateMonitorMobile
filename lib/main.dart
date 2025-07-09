// lib/main.dart

import 'package:flutter/material.dart';
import 'package:heart_rate_monitor_mobile/widgets/heart_rate_overlay.dart';
import 'package:provider/provider.dart';
import 'services/ble_service.dart';
import 'screens/home_screen.dart';

// 应用的主入口
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

// 悬浮窗的独立入口
@pragma("vm:entry-point")
void overlayMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(
    debugShowCheckedModeBanner: false,
    home: HeartRateOverlay(),
  ));
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => BleService()..autoConnect(), // 创建并初始化蓝牙服务
      child: MaterialApp(
        title: '心率监控器',
        theme: ThemeData(
          primarySwatch: Colors.indigo,
          scaffoldBackgroundColor: const Color(0xFFF8F9FA),
          cardColor: Colors.white,
          appBarTheme: const AppBarTheme(
            backgroundColor: Colors.transparent,
            elevation: 0,
            foregroundColor: Colors.black87,
            iconTheme: IconThemeData(color: Colors.black87),
          ),
        ),
        home: const HomeScreen(),
      ),
    );
  }
}