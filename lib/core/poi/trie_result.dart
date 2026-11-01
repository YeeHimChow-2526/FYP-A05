import 'poi.dart';

sealed class TrieResult {
  const TrieResult();
}

class Hit extends TrieResult {
  const Hit(this.poi);
  final Poi poi;
}

class Ambiguous extends TrieResult {
  const Ambiguous(this.candidates, {this.isFuzzy = false});
  final List<Poi> candidates;
  final bool isFuzzy;
}

class Miss extends TrieResult {
  const Miss();
}

const TrieResult trieMiss = Miss();
