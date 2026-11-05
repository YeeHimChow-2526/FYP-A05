import 'dart:async';

import 'package:flutter/services.dart';

import '../core/nav/fake_nav_engine.dart';
import '../core/nav/google_nav_engine.dart';
import '../core/nav/nav_engine.dart';
import '../core/net/api_client.dart';
import '../core/net/channel_fixture_transport.dart';
import '../core/net/http_api_transport.dart';
import '../core/platform/channel_prefs_port.dart';
import '../core/poi/local_poi_trie.dart';
import '../core/poi/poi_name_resolver.dart';
import '../core/poi/stt_typo.dart';
import '../core/trip/api_client_sync_api.dart';
import '../core/trip/today_trip_store.dart';
import '../core/trip/today_trip_sync.dart';
import '../core/trip/trip_controller.dart';
import '../platform/trip_platform_channel.dart';

class TripRuntime {
  TripRuntime._();

  static final TripRuntime instance = TripRuntime._();

  TripController? controller;
  TodayTripStore? store;
  TodayTripSync? sync;
  ChannelTripPlatform? platform;
  NavEngine? navEngine;
  Future<void>? _attachFuture;

  Future<void> attach() {
    final current = _attachFuture;
    if (current != null) return current;
    final future = _attach();
    _attachFuture = future;
    return future.catchError((Object error, StackTrace stack) {
      if (identical(_attachFuture, future)) _attachFuture = null;
      Error.throwWithStackTrace(error, stack);
    });
  }

  Future<void> _attach() async {
    final tripPlatform = ChannelTripPlatform(MethodChannel(ChannelTripPlatform.name));
    final config = await tripPlatform.transportConfig();
    final prefs = ChannelPrefsPort(
      onWrite: (key, value) {
        if (value == null) {
          tripPlatform.prefsRemove(key);
        } else {
          tripPlatform.prefsPut(key, value);
        }
      },
    );
    final tripStore = TodayTripStore(prefs, nowMs: () => DateTime.now().millisecondsSinceEpoch);
    final seed = await tripPlatform.prefsLoad();
    prefs.hydrate(seed);
    tripStore.reloadFromPrefs();
    final rawBase = config['baseUrl'];
    final baseUrl = rawBase is String ? rawBase : '';
    final rawKey = config['apiKey'];
    final useReal = config['useRealApi'] == true;
    final transport = useReal
        ? HttpApiTransport(
            baseUrl: baseUrl,
            apiKey: rawKey is String ? rawKey : '',
            allowLocalCleartext: baseUrl.startsWith('http://'),
          )
        : ChannelFixtureTransport(tripPlatform.fixturePost);
    final api = ApiClient(transport);
    final network = TripNetwork(true);
    final tripSync = TodayTripSync(tripStore, ApiClientSyncApi(api), network, nowMs: () => DateTime.now().millisecondsSinceEpoch);
    PoiNameResolver? resolver;
    try {
      final raw = await rootBundle.loadString('assets/poi/hk_poi_master.json');
      resolver = PoiNameResolver(trie: LocalPoiTrie.fromJsonString(raw), typo: SttTypoCorpus(const {}));
    } catch (_) {
      resolver = null;
    }
    final engine = config['useRealNavigation'] == true ? GoogleNavEngine() : FakeNavEngine();
    final tripController = TripController(
      platform: tripPlatform,
      api: api,
      store: tripStore,
      sync: tripSync,
      nowMs: () => DateTime.now().millisecondsSinceEpoch,
      delay: (ms) => Future<void>.delayed(Duration(milliseconds: ms)),
      network: network,
      resolver: resolver,
      navEngine: engine,
    );
    navEngine = engine;
    tripPlatform.bind(tripController);
    controller = tripController;
    store = tripStore;
    sync = tripSync;
    platform = tripPlatform;
  }
}
