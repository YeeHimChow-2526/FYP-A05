class TodayTripRecord {
  const TodayTripRecord({
    required this.sessionId,
    required this.carparkId,
    required this.carparkName,
    required this.parkedAtEpochMs,
    required this.status,
    required this.confirmSyncPending,
    required this.feedbackSubmitted,
    this.contractVersion = 1,
    this.lockRevision,
    this.destinationEpoch,
    this.syncRejected,
  });

  /// 泊車事件建立嗰刻（engine-off）鎖低嘅身份；之後重播原封不動。
  /// contractVersion 2 = 新事件（伺服器要求精確 lock_revision／destination_epoch，冇時間 fallback）；
  /// 1 = 舊版 App 留低嘅紀錄（冇身份）。
  final int contractVersion;

  /// 事件建立時持有嘅鎖 revision；null＝當時冇（已確認）鎖。
  final int? lockRevision;
  final int? destinationEpoch;

  /// 伺服器明確拒絕（例如 STALE_REVISION）→ 唔再重試；值係錯誤碼。
  final String? syncRejected;

  final String sessionId;
  final String carparkId;
  final String carparkName;
  final int parkedAtEpochMs;
  final String status;
  final bool confirmSyncPending;
  final bool feedbackSubmitted;

  TodayTripRecord copyWith({
    String? carparkId,
    String? carparkName,
    int? parkedAtEpochMs,
    String? status,
    bool? confirmSyncPending,
    bool? feedbackSubmitted,
    String? syncRejected,
    bool clearSyncRejected = false,
  }) {
    return TodayTripRecord(
      sessionId: sessionId,
      carparkId: carparkId ?? this.carparkId,
      carparkName: carparkName ?? this.carparkName,
      parkedAtEpochMs: parkedAtEpochMs ?? this.parkedAtEpochMs,
      status: status ?? this.status,
      confirmSyncPending: confirmSyncPending ?? this.confirmSyncPending,
      feedbackSubmitted: feedbackSubmitted ?? this.feedbackSubmitted,
      contractVersion: contractVersion,
      lockRevision: lockRevision,
      destinationEpoch: destinationEpoch,
      syncRejected: clearSyncRejected ? null : (syncRejected ?? this.syncRejected),
    );
  }

  Map<String, Object?> toJson() => {
        'sessionId': sessionId,
        'carparkId': carparkId,
        'carparkName': carparkName,
        'parkedAtEpochMs': parkedAtEpochMs,
        'status': status,
        'confirmSyncPending': confirmSyncPending,
        'feedbackSubmitted': feedbackSubmitted,
        'contractVersion': contractVersion,
        'lockRevision': lockRevision,
        'destinationEpoch': destinationEpoch,
        'syncRejected': syncRejected,
      };

  static TodayTripRecord? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final sessionId = raw['sessionId'];
    if (sessionId is! String || sessionId.isEmpty) return null;
    return TodayTripRecord(
      sessionId: sessionId,
      carparkId: raw['carparkId'] is String ? raw['carparkId'] as String : '',
      carparkName: raw['carparkName'] is String ? raw['carparkName'] as String : '',
      parkedAtEpochMs: raw['parkedAtEpochMs'] is int ? raw['parkedAtEpochMs'] as int : 0,
      status: raw['status'] is String ? raw['status'] as String : TodayTripStoreStatus.candidate,
      confirmSyncPending: raw['confirmSyncPending'] == true,
      feedbackSubmitted: raw['feedbackSubmitted'] == true,
      contractVersion: raw['contractVersion'] is int ? raw['contractVersion'] as int : 1,
      lockRevision: raw['lockRevision'] is int ? raw['lockRevision'] as int : null,
      destinationEpoch: raw['destinationEpoch'] is int ? raw['destinationEpoch'] as int : null,
      syncRejected: raw['syncRejected'] is String ? raw['syncRejected'] as String : null,
    );
  }
}

class TodayTripStoreStatus {
  static const String candidate = 'CANDIDATE';
  static const String confirmed = 'CONFIRMED';
}
