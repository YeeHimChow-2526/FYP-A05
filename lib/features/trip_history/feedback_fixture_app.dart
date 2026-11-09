import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:copilot/features/trip_history/today_trip_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const String feedbackFixtureArg = 'hkp_feedback_fixture';
const MethodChannel feedbackFixtureChannel = MethodChannel('hkp_feedback_fixture');

/// 測試注入先寫入兩列。已有同一 session 就保持，重開唔會蓋過拒絕或已提交。
void seedFeedbackFixtureIfEmpty(TodayTripStore store, int nowMs) {
  store.purgeIfNewHktDay(nowMs);
  if (store.find('fixture-ok') != null || store.find('fixture-rej') != null) return;
  store.upsertCandidate('fixture-ok', 'CP_FAKE_OK', '測試注入・可提交', nowMs);
  store.markConfirmed('fixture-ok', syncPending: false, atMs: nowMs);
  store.markSyncSent('fixture-ok');
  store.upsertCandidate('fixture-rej', 'CP_FAKE_REJ', '測試注入・確認被拒', nowMs);
  store.markConfirmed('fixture-rej', syncPending: true, atMs: nowMs);
  store.markSyncRejected('fixture-rej', 'HTTP_409');
}

class _FixturePrefs implements PrefsPort {
  final Map<String, String?> _mem = {};

  Future<void> hydrate() async {
    for (final key in [NativeConfig.TODAY_TRIPS_JSON_KEY, NativeConfig.TODAY_TRIPS_HKT_DATE_KEY]) {
      _mem[key] = await feedbackFixtureChannel.invokeMethod<String>('get', {'key': key});
    }
  }

  @override
  String? getString(String key) => _mem[key];

  @override
  void putString(String key, String value) {
    _mem[key] = value;
    feedbackFixtureChannel.invokeMethod<void>('put', {'key': key, 'value': value});
  }

  @override
  void remove(String key) {
    _mem.remove(key);
    feedbackFixtureChannel.invokeMethod<void>('remove', {'key': key});
  }
}

class _CountingSync implements SyncApi {
  _CountingSync(this.onCount);

  final VoidCallback onCount;
  int tripPosts = 0;
  int syncPosts = 0;

  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) async {
    syncPosts += 1;
    debugPrint('HKP_FEEDBACK_FIXTURE confirm posts=$syncPosts session=$sessionId');
    onCount();
    return const CallOutcome(
      succeeded: true,
      httpStatus: 200,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    );
  }

  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) async {
    tripPosts += 1;
    debugPrint('HKP_FEEDBACK_FIXTURE trip_report posts=$tripPosts session=$sessionId');
    onCount();
    return const CallOutcome(
      succeeded: true,
      httpStatus: 200,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    );
  }
}

class _AlwaysOnline implements NetworkStatusProvider {
  @override
  bool isOnline() => true;
}

class FeedbackFixtureApp extends StatefulWidget {
  const FeedbackFixtureApp({super.key});

  @override
  State<FeedbackFixtureApp> createState() => _FeedbackFixtureAppState();
}

class _FeedbackFixtureAppState extends State<FeedbackFixtureApp> {
  TodayTripStore? _store;
  TodayTripSync? _sync;
  _CountingSync? _api;
  String? _resendText;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final prefs = _FixturePrefs();
      await prefs.hydrate();
      final store = TodayTripStore(prefs);
      final api = _CountingSync(() {
        if (mounted) setState(() {});
      });
      seedFeedbackFixtureIfEmpty(store, DateTime.now().millisecondsSinceEpoch);
      if (!mounted) return;
      setState(() {
        _store = store;
        _api = api;
        _sync = TodayTripSync(store, api, _AlwaysOnline());
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error);
    }
  }

  Future<void> _resend() async {
    final sync = _sync;
    final store = _store;
    if (sync == null || store == null) return;
    final row = store.find('fixture-ok');
    final outcome = await sync.submitReport(
      '{"session_id":"fixture-ok","carpark_id":"CP_FAKE_OK","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":"","trip_timestamp":${(row?.parkedAtEpochMs ?? 0) ~/ 1000}}',
    );
    debugPrint('HKP_FEEDBACK_FIXTURE resend posted=${outcome.posted} reason=${outcome.reason} trip_posts=${_api?.tripPosts}');
    if (!mounted) return;
    setState(() => _resendText = outcome.posted ? '再送竟然送出' : '再送未出站：${outcome.reason}');
  }

  @override
  Widget build(BuildContext context) {
    final api = _api;
    return MaterialApp(
      title: '泊車副駕測試注入',
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: AppColors.surface,
        colorScheme: const ColorScheme.dark(surface: AppColors.surface, primary: AppColors.amberSteady),
      ),
      home: Scaffold(
        backgroundColor: AppColors.surface,
        body: _error != null
            ? Center(child: Text('測試注入未能讀取本機狀態', style: TextStyle(color: AppColors.textPrimary)))
            : _store == null || _sync == null
                ? const Center(child: CircularProgressIndicator())
                : Column(
                    children: [
                      const SizedBox(height: 28),
                      const Text(
                        '測試注入，不會呼叫雲端',
                        key: ValueKey('feedback_fixture_banner'),
                        style: TextStyle(color: AppColors.textPrimary, fontSize: 18),
                      ),
                      Text(
                        'trip-report 次數 ${api?.tripPosts ?? 0}',
                        key: const ValueKey('feedback_fixture_count'),
                        style: const TextStyle(color: AppColors.textSecondary),
                      ),
                      if (_resendText != null)
                        Text(_resendText!, key: const ValueKey('feedback_fixture_resend'), style: const TextStyle(color: AppColors.textSecondary)),
                      Align(
                        alignment: Alignment.centerRight,
                        child: TextButton(
                          key: const ValueKey('feedback_fixture_resend_button'),
                          onPressed: _resend,
                          child: const Text('測試注入：對已提交再送一次'),
                        ),
                      ),
                      Expanded(child: TodayTripScreen(store: _store, sync: _sync)),
                    ],
                  ),
      ),
    );
  }
}
