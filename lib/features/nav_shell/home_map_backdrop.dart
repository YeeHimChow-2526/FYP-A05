import 'package:copilot/core/nav/nav_engine.dart';
import 'package:flutter/material.dart';

class HomeMapBackdrop extends StatelessWidget {
  const HomeMapBackdrop({super.key, required this.navEngine});

  final NavEngine? navEngine;

  static const Key backdropKey = Key('home_map_backdrop');

  @override
  Widget build(BuildContext context) {
    final engine = navEngine;
    if (engine == null) {
      return const ColoredBox(
        key: backdropKey,
        color: Color(0xFF1B4F72),
        child: Align(
          alignment: Alignment.center,
          child: Text(
            'HK map\n22.3193, 114.1694',
            textAlign: TextAlign.center,
            style: TextStyle(color: Color(0xFFE0E0E0), fontSize: 18),
          ),
        ),
      );
    }
    return KeyedSubtree(
      key: backdropKey,
      child: engine.buildNavView(),
    );
  }
}
