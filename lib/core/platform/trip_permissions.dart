import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/core/voice/voice_capability.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter/services.dart';

/// t1253 權限政策（同 Kotlin TripPermissions 一致）：
/// 精確位置＋系統定位＝必要；通知／藍牙／咪＝可選；懸浮窗、通知存取權唔問；冇背景位置。
enum TripPermissionKind { location, notifications, bluetooth, microphone }

enum PermissionState { granted, approximate, denied, notApplicable, unknown }

class PermissionInfo {
  const PermissionInfo(this.state, {this.canRequest = false, this.askedBefore = false});
  final PermissionState state;

  /// Android 仲會唔會彈系統對話框；false 而又未批＝要去系統設定（唔聲稱「永久拒絕」）。
  final bool canRequest;
  final bool askedBefore;

  bool get granted => state == PermissionState.granted;
  bool get needsSettings => !granted && state != PermissionState.notApplicable && !canRequest;

  static PermissionInfo fromWire(Object? raw) {
    if (raw is! Map) return const PermissionInfo(PermissionState.unknown);
    final state = switch (raw['state']) {
      'granted' => PermissionState.granted,
      'approximate' => PermissionState.approximate,
      'denied' => PermissionState.denied,
      'notApplicable' => PermissionState.notApplicable,
      _ => PermissionState.unknown,
    };
    return PermissionInfo(state, canRequest: raw['canRequest'] == true, askedBefore: raw['askedBefore'] == true);
  }
}

class PermissionSnapshot {
  const PermissionSnapshot(this.items, {required this.locationServicesOn, this.nativeAvailable = true});
  final Map<TripPermissionKind, PermissionInfo> items;
  final bool locationServicesOn;

  /// false＝唔係 Android（或測試冇原生通道）；唔阻擋。
  final bool nativeAvailable;

  PermissionInfo operator [](TripPermissionKind k) => items[k] ?? const PermissionInfo(PermissionState.unknown);
  bool get startAllowed => !nativeAvailable || (this[TripPermissionKind.location].granted && locationServicesOn);
}

class VoiceFacts {
  const VoiceFacts({
    required this.ttsEngine,
    required this.recognizer,
    required this.recordAudio,
    required this.simulatedInput,
  });
  final bool ttsEngine;
  final bool recognizer;
  final bool recordAudio;

  /// debug：adb INJECT_UTTERANCE 模擬語音輸入，唔用咪。
  final bool simulatedInput;

  static const none = VoiceFacts(ttsEngine: false, recognizer: false, recordAudio: false, simulatedInput: false);
}

/// 三個誠實模式：manualOnly（冇 TTS）／announceManual（TTS 提示＋手動）／fullVoice（TTS＋語音輸入）。
/// 語音輸入：release 要辨識器＋咪權限；debug 用模擬輸入。用戶關咗語音輸入就唔會開。
CapabilitySnapshot deriveVoiceCapability(VoiceFacts f, {required bool userVoiceEnabled}) {
  final tts = f.ttsEngine ? CapabilityStatus.available : CapabilityStatus.unsupported;
  final sttReady = f.simulatedInput || (f.recognizer && f.recordAudio);
  final stt = sttReady ? CapabilityStatus.available : CapabilityStatus.unsupported;
  final TripVoiceMode mode;
  if (!f.ttsEngine) {
    mode = TripVoiceMode.manualOnly;
  } else if (sttReady && userVoiceEnabled) {
    mode = TripVoiceMode.fullVoice;
  } else {
    mode = TripVoiceMode.announceManual;
  }
  return CapabilitySnapshot(
    stt: stt,
    tts: tts,
    mode: mode,
    voiceInputEnabled: mode == TripVoiceMode.fullVoice,
    canListen: f.recognizer && f.recordAudio && userVoiceEnabled,
  );
}

/// 原生通道（overlay channel）。MissingPluginException＝非 Android／冇原生：唔阻擋。
class TripPermissionPort {
  TripPermissionPort([MethodChannel? channel]) : channel = channel ?? OverlayChannel.channel;
  final MethodChannel channel;

  static PermissionSnapshot parse(Object? raw) {
    if (raw is! Map) {
      return const PermissionSnapshot({}, locationServicesOn: false);
    }
    return PermissionSnapshot(
      {for (final k in TripPermissionKind.values) k: PermissionInfo.fromWire(raw[k.name])},
      locationServicesOn: raw['locationServicesOn'] == true,
    );
  }

  Future<PermissionSnapshot> states() async {
    try {
      return parse(await channel.invokeMethod<dynamic>('getTripPermissions'));
    } on MissingPluginException {
      return const PermissionSnapshot({}, locationServicesOn: true, nativeAvailable: false);
    }
  }

  Future<PermissionInfo> request(TripPermissionKind kind) async {
    try {
      return PermissionInfo.fromWire(await channel.invokeMethod<dynamic>('requestTripPermission', {'kind': kind.name}));
    } on MissingPluginException {
      return const PermissionInfo(PermissionState.granted);
    } on PlatformException {
      return const PermissionInfo(PermissionState.unknown);
    }
  }

  Future<bool> openAppSettings() async {
    try {
      return await channel.invokeMethod<bool>('openAppSettings') ?? false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<VoiceFacts> voiceFacts() async {
    try {
      final raw = await channel.invokeMethod<dynamic>('getVoiceFacts');
      if (raw is! Map) return VoiceFacts.none;
      return VoiceFacts(
        ttsEngine: raw['ttsEngine'] == true,
        recognizer: raw['recognizer'] == true,
        recordAudio: raw['recordAudio'] == true,
        simulatedInput: raw['simulatedInput'] == true,
      );
    } on MissingPluginException {
      return VoiceFacts.none;
    }
  }
}

enum StartGateResult { allowed, locationDenied, locationNeedsSettings, locationServicesOff }

/// 撳「出發」時嘅流程：精確位置（未批先問）→ 通知（可選，未問過先問一次）→ 結果。
/// 由用戶手勢觸發；冇批就唔會開服務。
class StartPermissionFlow {
  StartPermissionFlow(this.port);
  final TripPermissionPort port;

  /// [explainLocation]：彈系統對話框之前嘅簡短解釋；回 false＝用戶取消。
  Future<StartGateResult> ensureForStart({Future<bool> Function()? explainLocation}) async {
    var snap = await port.states();
    if (!snap.nativeAvailable) return StartGateResult.allowed;
    var loc = snap[TripPermissionKind.location];
    if (!loc.granted) {
      if (!loc.canRequest) return StartGateResult.locationNeedsSettings;
      if (explainLocation != null && !await explainLocation()) return StartGateResult.locationDenied;
      loc = await port.request(TripPermissionKind.location);
      snap = await port.states(); // 對話框之後重新讀真狀態
      loc = snap[TripPermissionKind.location];
      if (!loc.granted) {
        return loc.canRequest ? StartGateResult.locationDenied : StartGateResult.locationNeedsSettings;
      }
    }
    if (!snap.locationServicesOn) return StartGateResult.locationServicesOff;
    final notif = snap[TripPermissionKind.notifications];
    if (notif.state == PermissionState.denied && !notif.askedBefore && notif.canRequest) {
      await port.request(TripPermissionKind.notifications); // 結果唔影響開程
    }
    return StartGateResult.allowed;
  }
}

String permissionLabel(TripPermissionKind k) => switch (k) {
      TripPermissionKind.location => '精確位置（必要）',
      TripPermissionKind.notifications => '通知（可選）',
      TripPermissionKind.bluetooth => '藍牙（可選：熄匙偵測）',
      TripPermissionKind.microphone => '咪（可選：語音指令）',
    };

String permissionStateLabel(PermissionInfo i) => switch (i.state) {
      PermissionState.granted => '已批',
      PermissionState.approximate => '只有大約位置',
      PermissionState.denied => i.needsSettings ? '未批（要去系統設定）' : '未批',
      PermissionState.notApplicable => '呢個 Android 版本唔使',
      PermissionState.unknown => '未知',
    };
