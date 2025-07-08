// lib/screens/home_screen.dart

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import '../services/ble_service.dart';
import 'settings_screen.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<BleService>(
      builder: (context, bleService, child) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('心率监控器'),
            actions: [
              if (bleService.isConnected)
                IconButton(
                  icon: const Icon(Icons.link_off, color: Colors.red),
                  tooltip: '断开连接',
                  onPressed: () => bleService.disconnectDevice(),
                ),
              IconButton(
                icon: const Icon(Icons.settings_outlined),
                tooltip: '设置',
                onPressed: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (context) => const SettingsScreen()),
                  );
                },
              ),
            ],
          ),
          body: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            child: Column(
              children: [
                _buildHeartRateDisplay(context, bleService),
                const SizedBox(height: 24),
                _buildStatusCard(context, bleService),
                const SizedBox(height: 16),
                if (!bleService.isConnected)
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('可连接的设备', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                  ),
                if (!bleService.isConnected) const SizedBox(height: 8),
                if (!bleService.isConnected) Expanded(child: _buildDeviceList(bleService)),
              ],
            ),
          ),
          floatingActionButton: bleService.isConnected
              ? null
              : FloatingActionButton(
                  onPressed: () => bleService.startScan(),
                  child: bleService.isScanning
                      ? const CircularProgressIndicator(color: Colors.white, strokeWidth: 2)
                      : const Icon(Icons.search),
                ),
        );
      },
    );
  }

  Widget _buildHeartRateDisplay(BuildContext context, BleService bleService) {
    final bool isConnected = bleService.isConnected;
    
    return Container(
      width: double.infinity,
      height: 220,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: isConnected
              ? [Colors.redAccent.shade100, Colors.red.shade400]
              : [Colors.grey.shade400, Colors.grey.shade600],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: isConnected ? Colors.red.withAlpha(77) : Colors.black.withAlpha(51),
            blurRadius: 15,
            offset: const Offset(0, 5),
          ),
        ],
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            isConnected ? '❤️' : '💔',
            style: const TextStyle(fontSize: 40, color: Colors.white),
          ),
          const SizedBox(height: 8),
          Text(
            isConnected ? '${bleService.heartRate}' : '--',
            style: const TextStyle(
              fontSize: 80,
              fontWeight: FontWeight.bold,
              color: Colors.white,
            ),
          ),
          const Text(
            'BPM',
            style: TextStyle(fontSize: 20, color: Colors.white70),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusCard(BuildContext context, BleService bleService) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: Theme.of(context).cardColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.grey.shade200),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (bleService.isScanning)
            const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
          else
            Icon(
              bleService.isConnected ? Icons.bluetooth_connected : Icons.bluetooth_disabled,
              color: bleService.isConnected ? Colors.green : Colors.red,
              size: 20,
            ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              bleService.statusMessage,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w500),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDeviceList(BleService bleService) {
    // 过滤掉已收藏的设备，避免重复显示
    final otherDevices = bleService.scanResults
        .where((r) => r.device.remoteId.toString() != bleService.favoriteDeviceId)
        .toList();
    
    // 检查收藏的设备是否在线
    final isFavoriteOnline = bleService.scanResults
        .any((r) => r.device.remoteId.toString() == bleService.favoriteDeviceId);

    return ListView(
      children: [
        // 如果有收藏的设备，总是在顶部显示它
        if (bleService.favoriteDeviceId != null)
          _buildFavoriteDeviceTile(bleService, isFavoriteOnline),
        
        // 然后显示其他扫描到的设备
        ...otherDevices.map((result) => _buildDeviceTile(bleService, result)),
      ],
    );
  }

  // 为收藏的设备创建一个特殊的 Tile
  Widget _buildFavoriteDeviceTile(BleService bleService, bool isOnline) {
    ScanResult? onlineResult;
    if(isOnline) {
      onlineResult = bleService.scanResults.firstWhere((r) => r.device.remoteId.toString() == bleService.favoriteDeviceId);
    }

    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      margin: const EdgeInsets.symmetric(vertical: 6, horizontal: 0),
      color: isOnline ? Colors.white : Colors.white.withOpacity(0.6),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
        title: Text(
          bleService.favoriteDeviceName ?? '已收藏的设备',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: isOnline ? Colors.black : Colors.grey.shade600,
          ),
        ),
        subtitle: Text(bleService.favoriteDeviceId!),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            isOnline
                ? _getSignalIcon(onlineResult!.rssi)
                : const Icon(Icons.signal_cellular_off, color: Colors.grey),
            const SizedBox(width: 8),
            IconButton(
              icon: const Icon(Icons.star, color: Colors.amber),
              onPressed: () => bleService.toggleFavoriteDevice(
                bleService.favoriteDeviceId!,
                bleService.favoriteDeviceName ?? '',
              ),
            ),
          ],
        ),
        onTap: isOnline ? () => bleService.connectToDevice(onlineResult!.device) : null,
      ),
    );
  }

  // 为普通扫描到的设备创建一个 Tile
  Widget _buildDeviceTile(BleService bleService, ScanResult result) {
    final deviceId = result.device.remoteId.toString();
    final deviceName = result.device.platformName.isNotEmpty ? result.device.platformName : '未知设备';

    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      margin: const EdgeInsets.symmetric(vertical: 6, horizontal: 0),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
        title: Text(deviceName, style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(deviceId),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _getSignalIcon(result.rssi),
            const SizedBox(width: 8),
            IconButton(
              icon: const Icon(Icons.star_border, color: Colors.grey),
              onPressed: () => bleService.toggleFavoriteDevice(deviceId, deviceName),
            ),
          ],
        ),
        onTap: () => bleService.connectToDevice(result.device),
      ),
    );
  }


  Widget _getSignalIcon(int rssi) {
    IconData icon;
    Color color;
    if (rssi > -60) {
      icon = Icons.signal_cellular_alt;
      color = Colors.green;
    } else if (rssi > -80) {
      icon = Icons.signal_cellular_alt_2_bar;
      color = Colors.amber.shade700;
    } else {
      icon = Icons.signal_cellular_alt_1_bar;
      color = Colors.red;
    }
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(icon, color: color),
        Text('${rssi}dBm', style: TextStyle(fontSize: 10, color: color)),
      ],
    );
  }
}