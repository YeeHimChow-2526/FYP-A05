import '../net/api_client.dart';
import 'sync_api.dart';

class ApiClientSyncApi implements SyncApi {
  ApiClientSyncApi(this.client);

  final ApiClient client;

  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) {
    return client.syncDriverConfirmed(sessionId, timestampSec, identity: identity);
  }

  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) {
    return client.submitTripReport(
      sessionId: sessionId,
      carparkId: carparkId,
      ratingTag: ratingTag,
      perkStatusTag: perkStatusTag,
      perkNotes: perkNotes,
      tripTimestampSec: tripTimestampSec,
    );
  }
}
