import 'dart:async';

import 'package:flutter/services.dart';

import '../core/net/api_client.dart';
import '../core/net/channel_fixture_transport.dart';
import '../core/net/http_api_transport.dart';
import '../core/poi/local_poi_trie.dart';
import '../core/poi/poi_name_resolver.dart';
import '../core/poi/stt_typo.dart';
import '../core/trip/api_client_sync_api.dart';
import '../core/trip/channel_prefs_port.dart';
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
  Future<void>? _attachFuture;

  Future<void> attach() {
    return _attachFuture ??= _attach();
  }

  Future<void> _attach() async {
    final platform = ChannelTripPlatform(MethodChannel(ChannelTripPlatform.name));
    final config = await platform.transportConfig();
    final seed = await platform.prefsLoad();
    final prefs = ChannelPrefsPort(
      seed: seed,
      onWrite: (key, value) {
        if (value == null) {
          platform.prefsRemove(key);
        } else {
          platform.prefsPut(key, value);
        }
      },
    );
    final tripStore = TodayTripStore(prefs, nowMs: () => DateTime.now().millisecondsSinceEpoch);
    final useReal = config['useRealApi'] == true;
    final transport = useReal
        ? HttpApiTransport(
            baseUrl: config['baseUrl'] as String? ?? '',
            apiKey: config['apiKey'] as String? ?? '',
            allowLocalCleartext: config['baseUrl'].toString().startsWith('http://'),
          )
        : ChannelFixtureTransport(platform.fixturePost);
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
    final tripController = TripController(
      platform: platform,
      api: api,
      store: tripStore,
      sync: tripSync,
      nowMs: () => DateTime.now().millisecondsSinceEpoch,
      delay: (ms) => Future<void>.delayed(Duration(milliseconds: ms)),
      network: network,
      resolver: resolver,
    );
    platform.bind(tripController);
    controller = tripController;
    store = tripStore;
    sync = tripSync;
  }
}
