// lib/main.dart

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
// import 'package:flutter_overlay_window/flutter_overlay_window.dart'; // <--- 已移除
import 'services/ble_service.dart';
import 'screens/home_screen.dart';
import 'widgets/overlay_widget.dart';

@pragma("vm:entry-point")
void overlayMain() {
  runApp(const MaterialApp(
    debugShowCheckedModeBanner: false,
    home: OverlayWidget(),
  ));
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
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