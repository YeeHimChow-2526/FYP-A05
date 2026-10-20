import 'package:flutter/services.dart';

typedef TripStateListener = void Function(String state, String dataJson);
typedef TodayTripsListener = void Function();
typedef TripEndedListener = void Function(String reason);
typedef MicSessionListener = void Function(bool isActive, String reason);

class OverlayChannel {
  static const String name = 'com.hkparking.copilot/overlay';
  static const MethodChannel channel = MethodChannel(name);

  static final List<TripStateListener> tripStateListeners = [];
  static final List<TodayTripsListener> todayTripsListeners = [];
  static final List<TripEndedListener> tripEndedListeners = [];
  static final List<MicSessionListener> micSessionListeners = [];

  static bool _attached = false;

  static void attach() {
    if (_attached) {
      return;
    }
    _attached = true;
    channel.setMethodCallHandler(_handle);
  }

  static void resetForTest() {
    _attached = false;
    tripStateListeners.clear();
    todayTripsListeners.clear();
    tripEndedListeners.clear();
    micSessionListeners.clear();
    channel.setMethodCallHandler(null);
  }

  static Future<void> startBubbleService() {
    return channel.invokeMethod<void>('startBubbleService');
  }

  static Future<dynamic> getPermissionStatus() {
    return channel.invokeMethod<dynamic>('getPermissionStatus');
  }

  static Future<dynamic> getVoiceCapability() {
    return channel.invokeMethod<dynamic>('getVoiceCapability');
  }

  static Future<void> startVoiceCapture({required bool enableHotword}) {
    return channel.invokeMethod<void>('startVoiceCapture', {
      'enableHotword': enableHotword,
    });
  }

  static Future<void> stopVoiceCapture() {
    return channel.invokeMethod<void>('stopVoiceCapture');
  }

  static Future<void> setPreferences({
    required String preference,
    required bool hotwordEnabled,
  }) {
    return channel.invokeMethod<void>('setPreferences', {
      'preference': preference,
      'hotwordEnabled': hotwordEnabled,
    });
  }

  static Future<dynamic> getTripState() {
    return channel.invokeMethod<dynamic>('getTripState');
  }

  static Future<dynamic> getTodayTrips() {
    return channel.invokeMethod<dynamic>('getTodayTrips');
  }

  static Future<void> confirmParkedCandidate(String sessionId) {
    return channel.invokeMethod<void>('confirmParkedCandidate', {
      'sessionId': sessionId,
    });
  }

  static Future<void> submitTripReport(String payloadJson) {
    return channel.invokeMethod<void>('submitTripReport', {
      'payloadJson': payloadJson,
    });
  }

  static Future<void> _handle(MethodCall call) async {
    final args = call.arguments;
    switch (call.method) {
      case 'onTripStateChanged':
        final map = Map<Object?, Object?>.from(args as Map);
        final state = map['state'] as String;
        final dataJson = map['dataJson'] as String;
        for (final listener in List<TripStateListener>.from(tripStateListeners)) {
          listener(state, dataJson);
        }
      case 'onTodayTripsChanged':
        for (final listener in List<TodayTripsListener>.from(todayTripsListeners)) {
          listener();
        }
      case 'onTripEnded':
        final reason = args is Map
            ? Map<Object?, Object?>.from(args)['reason'] as String
            : args as String;
        for (final listener in List<TripEndedListener>.from(tripEndedListeners)) {
          listener(reason);
        }
      case 'onMicSessionChanged':
        final map = Map<Object?, Object?>.from(args as Map);
        final isActive = map['isActive'] as bool;
        final reason = map['reason'] as String;
        for (final listener in List<MicSessionListener>.from(micSessionListeners)) {
          listener(isActive, reason);
        }
    }
  }
}
