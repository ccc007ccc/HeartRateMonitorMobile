// lib/services/ble_service.dart

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

// 用于本地存储的 Key
const String kFavoriteDeviceIdKey = 'favorite_device_id';
const String kFavoriteDeviceNameKey = 'favorite_device_name';
const String kAutoConnectKey = 'auto_connect_enabled';

class BleService extends ChangeNotifier {
  // 内部状态
  BluetoothDevice? _connectedDevice;
  StreamSubscription<List<int>>? _hrSubscription;
  StreamSubscription<BluetoothConnectionState>? _connectionStateSubscription;
  String? favoriteDeviceId;
  String? favoriteDeviceName;

  // 公开状态
  bool isScanning = false;
  final List<ScanResult> scanResults = [];
  bool get isConnected => _connectedDevice != null;
  int heartRate = 0;
  String statusMessage = "请先扫描并连接设备";

  BleService() {
    _loadFavoriteDevice();
    FlutterBluePlus.adapterState.listen((state) {
      if (state != BluetoothAdapterState.on) {
        statusMessage = "蓝牙未开启，请打开蓝牙后重试";
        if (isConnected) _cleanupConnection();
        notifyListeners();
      } else {
        if (!isConnected) autoConnect();
      }
    });
  }

  Future<void> _loadFavoriteDevice() async {
    final prefs = await SharedPreferences.getInstance();
    favoriteDeviceId = prefs.getString(kFavoriteDeviceIdKey);
    favoriteDeviceName = prefs.getString(kFavoriteDeviceNameKey);
    notifyListeners();
  }

  Future<void> _saveFavoriteDevice(String deviceId, String name) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(kFavoriteDeviceIdKey, deviceId);
    await prefs.setString(kFavoriteDeviceNameKey, name);
    favoriteDeviceId = deviceId;
    favoriteDeviceName = name;
    notifyListeners();
  }

  Future<void> _clearFavoriteDevice() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(kFavoriteDeviceIdKey);
    await prefs.remove(kFavoriteDeviceNameKey);
    favoriteDeviceId = null;
    favoriteDeviceName = null;
    notifyListeners();
  }

  void toggleFavoriteDevice(String deviceId, String name) {
    if (favoriteDeviceId == deviceId) {
      _clearFavoriteDevice();
      statusMessage = "已取消收藏";
    } else {
      _saveFavoriteDevice(deviceId, name);
      statusMessage = "设备已收藏";
    }
    notifyListeners();
  }

  Future<void> autoConnect() async {
    final prefs = await SharedPreferences.getInstance();
    final bool autoConnectEnabled = prefs.getBool(kAutoConnectKey) ?? false;

    if (autoConnectEnabled && favoriteDeviceId != null && !isConnected) {
      statusMessage = "正在自动连接收藏的设备...";
      notifyListeners();
      try {
        await FlutterBluePlus.startScan(timeout: const Duration(seconds: 5));
        final subscription = FlutterBluePlus.scanResults.listen((results) {
          for (var result in results) {
            if (result.device.remoteId.toString() == favoriteDeviceId) {
              FlutterBluePlus.stopScan();
              connectToDevice(result.device);
              break;
            }
          }
        });
        await Future.delayed(const Duration(seconds: 5));
        subscription.cancel();
      } finally {
        if (!isConnected) {
          statusMessage = "未找到已收藏的设备";
          notifyListeners();
        }
      }
    }
  }

  void startScan() async {
    if (FlutterBluePlus.adapterStateNow != BluetoothAdapterState.on) {
      statusMessage = "请先开启蓝牙";
      notifyListeners();
      return;
    }
    isScanning = true;
    statusMessage = "正在扫描设备...";
    scanResults.clear();
    notifyListeners();

    try {
      FlutterBluePlus.scanResults.listen((results) {
        for (var r in results) {
          final existingIndex = scanResults.indexWhere((sr) => sr.device.remoteId == r.device.remoteId);
          if (existingIndex == -1) {
            scanResults.add(r);
          } else {
            scanResults[existingIndex] = r;
          }
        }
        notifyListeners();
      });
      await FlutterBluePlus.startScan(timeout: const Duration(seconds: 10));
    } finally {
      isScanning = false;
      if (statusMessage.contains("扫描")) {
        statusMessage = scanResults.isEmpty ? "未发现任何设备" : "扫描结束";
      }
      notifyListeners();
    }
  }

  Future<void> connectToDevice(BluetoothDevice device) async {
    if (isScanning) FlutterBluePlus.stopScan();
    statusMessage = "正在连接到 ${device.platformName}...";
    notifyListeners();
    _connectionStateSubscription = device.connectionState.listen((state) {
      if (state == BluetoothConnectionState.disconnected) {
        _cleanupConnection(message: "设备已断开连接");
      }
    });
    try {
      await device.connect(timeout: const Duration(seconds: 15));
      _connectedDevice = device;
      statusMessage = "连接成功，正在查找服务...";
      heartRate = 0;
      notifyListeners();
      await _discoverServicesAndSubscribe();
    } catch (e) {
      _cleanupConnection(message: "连接失败: $e");
    }
  }

  void disconnectDevice() {
    _connectedDevice?.disconnect();
    _cleanupConnection(message: "已手动断开连接");
  }

  void _cleanupConnection({String? message}) {
    _hrSubscription?.cancel();
    _connectionStateSubscription?.cancel();
    _hrSubscription = null;
    _connectionStateSubscription = null;
    _connectedDevice = null;
    heartRate = 0;
    statusMessage = message ?? "已断开连接";
    notifyListeners();
  }

  Future<void> _discoverServicesAndSubscribe() async {
    final Guid heartRateServiceUuid = Guid("0000180d-0000-1000-8000-00805f9b34fb");
    final Guid heartRateMeasurementCharacteristicUuid = Guid("00002a37-0000-1000-8000-00805f9b34fb");

    if (_connectedDevice == null) return;
    try {
      List<BluetoothService> services = await _connectedDevice!.discoverServices();
      BluetoothCharacteristic? hrCharacteristic;
      for (var service in services) {
        if (service.uuid == heartRateServiceUuid) {
          for (var char in service.characteristics) {
            if (char.uuid == heartRateMeasurementCharacteristicUuid) {
              hrCharacteristic = char;
              break;
            }
          }
        }
      }
      if (hrCharacteristic != null) {
        await _subscribeToCharacteristic(hrCharacteristic);
        statusMessage = "已连接到设备，正在接收心率数据...";
      } else {
        _cleanupConnection(message: "错误：未在此设备上找到心率特征。");
      }
    } catch (e) {
      _cleanupConnection(message: "查找服务失败: $e");
    } finally {
      notifyListeners();
    }
  }

  Future<void> _subscribeToCharacteristic(BluetoothCharacteristic characteristic) async {
    await characteristic.setNotifyValue(true);
    _hrSubscription = characteristic.onValueReceived.listen((value) async {
      if (value.isEmpty) return;
      int newHeartRate = 0;
      final int flag = value[0];
      final bool is16bit = (flag & 0x01) != 0;
      if (is16bit) {
        newHeartRate = (value[2] << 8) | value[1];
      } else {
        newHeartRate = value[1];
      }
      heartRate = newHeartRate;
      notifyListeners();
      
    }, onError: (e) {
      _cleanupConnection(message: "接收数据时出错: $e");
    });
  }
}