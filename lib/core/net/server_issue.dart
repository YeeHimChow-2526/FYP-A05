import 'dart:convert';

/// 合約 v2 typed 伺服器回應 → 用戶動作／狀態（唔當網絡斷、唔變灰）。
/// 只有 CONTENTION（503）屬可重試；ApiClient 用同一 body（同一 idempotency_key）有限次重試。
enum ServerIssueKind { capacityUnavailable, staleState, sessionIdle, sessionEnded, sessionInvalid, unsupportedBackend, contention }

class ServerIssue {
  const ServerIssue(this.code, this.kind);

  final String code;
  final ServerIssueKind kind;

  static const _byCode = <String, ServerIssueKind>{
    'CAPACITY_UNKNOWN': ServerIssueKind.capacityUnavailable,
    'CAPACITY_NOT_READY': ServerIssueKind.capacityUnavailable,
    'CAPACITY_LEDGER_FULL': ServerIssueKind.capacityUnavailable,
    'STALE_EPOCH': ServerIssueKind.staleState,
    'STALE_REVISION': ServerIssueKind.staleState,
    'STALE_PARKED': ServerIssueKind.staleState,
    'IDEMPOTENCY_KEY_REUSED': ServerIssueKind.staleState,
    'SESSION_EXPIRED': ServerIssueKind.sessionIdle,
    'SESSION_CLOSED': ServerIssueKind.sessionEnded,
    'SESSION_NOT_FOUND': ServerIssueKind.sessionEnded,
    'SESSION_START_REQUIRED': ServerIssueKind.sessionInvalid,
    'SESSION_START_EXPIRED': ServerIssueKind.sessionInvalid,
    'SESSION_START_INVALID': ServerIssueKind.sessionInvalid,
    'SESSION_ID_REUSED': ServerIssueKind.sessionInvalid,
    'EPOCH_REQUIRED': ServerIssueKind.unsupportedBackend,
    'REVISION_REQUIRED': ServerIssueKind.unsupportedBackend,
    'UNSUPPORTED_BACKEND': ServerIssueKind.unsupportedBackend,
    'CONTENTION': ServerIssueKind.contention,
  };

  /// [httpStatus]＋body `status` → typed issue；LOCK_FULL／成功／普通網絡錯誤 → null。
  /// 422（請求合約被拒）或 404 冇 typed status（端點唔存在）＝ 後端版本唔支援。
  static ServerIssue? from(int? httpStatus, String? bodyJson) {
    if (httpStatus == null) return null;
    String? status;
    if (bodyJson != null && bodyJson.isNotEmpty) {
      try {
        final decoded = jsonDecode(bodyJson);
        if (decoded is Map && decoded['status'] is String) status = decoded['status'] as String;
      } catch (_) {}
    }
    if ((httpStatus == 409 || httpStatus == 404 || httpStatus == 503) && status != null) {
      final kind = _byCode[status];
      if (kind != null && (httpStatus != 503 || kind == ServerIssueKind.contention)) return ServerIssue(status, kind);
    }
    if (httpStatus == 422 || (httpStatus == 404 && status == null)) {
      return const ServerIssue('UNSUPPORTED_BACKEND', ServerIssueKind.unsupportedBackend);
    }
    return null;
  }

  bool get retryable => kind == ServerIssueKind.contention;

  /// 狀態列文字（用戶要做乜）。
  String get message => switch (kind) {
        ServerIssueKind.capacityUnavailable => '呢個停車場暫時留唔到位，請揀第二張卡',
        ServerIssueKind.staleState => '行程狀態已更新，請再搵一次車位',
        ServerIssueKind.sessionIdle => '閒置太耐，請再搵一次車位',
        ServerIssueKind.sessionEnded => '伺服器已結束呢個行程，請結束後重新開程',
        ServerIssueKind.sessionInvalid => '行程識別已失效，請結束後重新開程',
        ServerIssueKind.unsupportedBackend => '伺服器版本唔支援，暫時用唔到留位',
        ServerIssueKind.contention => '伺服器繁忙，請稍後再試',
      };

  /// 口播（driverAsked）。
  String get spoken => message;
}
