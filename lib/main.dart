// lib/main.dart

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'services/ble_service.dart';
import 'screens/home_screen.dart';

void main() {
  // 确保 Flutter 部件已初始化
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      // 在服务创建后立即尝试自动连接
      create: (_) => BleService()..autoConnect(),
      child: MaterialApp(
        title: '心率监控器',
        theme: ThemeData(
          primarySwatch: Colors.blue,
          scaffoldBackgroundColor: const Color(0xFFF8F9FA),
          cardColor: Colors.white,
          appBarTheme: const AppBarTheme(
            backgroundColor: Color(0xFFF8F9FA),
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