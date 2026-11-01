import '../net/api_client.dart';

class ConfirmOutcome {
  const ConfirmOutcome({required this.ok, required this.syncPosted, this.reason});

  final bool ok;
  final bool syncPosted;
  final String? reason;
}

class ReportOutcome {
  const ReportOutcome({required this.ok, required this.posted, this.reason});

  final bool ok;
  final bool posted;
  final String? reason;
}

abstract class SyncApi {
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec);
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  });
}

abstract class NetworkStatusProvider {
  bool isOnline();
}
