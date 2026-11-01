class TodayTripRecord {
  const TodayTripRecord({
    required this.sessionId,
    required this.carparkId,
    required this.carparkName,
    required this.parkedAtEpochMs,
    required this.status,
    required this.confirmSyncPending,
    required this.feedbackSubmitted,
  });

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
  }) {
    return TodayTripRecord(
      sessionId: sessionId,
      carparkId: carparkId ?? this.carparkId,
      carparkName: carparkName ?? this.carparkName,
      parkedAtEpochMs: parkedAtEpochMs ?? this.parkedAtEpochMs,
      status: status ?? this.status,
      confirmSyncPending: confirmSyncPending ?? this.confirmSyncPending,
      feedbackSubmitted: feedbackSubmitted ?? this.feedbackSubmitted,
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
    );
  }
}

class TodayTripStoreStatus {
  static const String candidate = 'CANDIDATE';
  static const String confirmed = 'CONFIRMED';
}
