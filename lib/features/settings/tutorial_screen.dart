import 'package:copilot/core/constants/app_colors.dart';
import 'package:flutter/material.dart';

/// 新手教學（t1253 現行版，4 頁）：現行流程、示範數據誠實聲明、權限規則、到步流程。
const tutorialPages = <(String, String)>[
  ('點樣用', '輸入目的地 → 撳「出發」→ 開車。離目的地大約 1 km，App 會推介停車場（P）。第 1、2 次確認即刻轉線；第 3 次起要再撳「確認」或者辨識到「好」先轉。未確認可以撳「取消」，舊路線保持。'),
  ('數據同導航', '正常模式用即時數據同 Google 導航。設定入面開 DEMO 先用示範數據。示範卡會標明，唔當即時空位。導航提示跟手機嘅導航語言，App 自己嘅口播先係粵語。'),
  ('權限', '必要：精確位置（只喺行程期間用，唔背景追蹤）。可選：通知、藍牙（偵測熄匙）、咪（辨識可用先聽指令；冇就用畫面掣）。可以喺設定頁隨時改。'),
  ('到步', '藍牙熄匙而且喺目標附近，今日行程先出現「可能已泊入」。撳「確認已泊妥」之後先可以交反饋。手動結束唔會變成已泊妥。清單喺手機，完成當日香港時間午夜前先填得。'),
];

class TutorialScreen extends StatefulWidget {
  const TutorialScreen({super.key});

  @override
  State<TutorialScreen> createState() => _TutorialScreenState();
}

class _TutorialScreenState extends State<TutorialScreen> {
  final _pages = PageController();
  int _index = 0;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final last = _index == tutorialPages.length - 1;
    return Scaffold(
      backgroundColor: AppColors.surface,
      appBar: AppBar(title: Text('新手教學 ${_index + 1}/${tutorialPages.length}')),
      body: PageView(
        key: const Key('tutorial_pages'),
        controller: _pages,
        onPageChanged: (i) => setState(() => _index = i),
        children: [
          for (final page in tutorialPages)
            Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(page.$1, style: TextStyle(color: AppColors.textPrimary, fontSize: 22)),
                  const SizedBox(height: 16),
                  Text(page.$2, style: TextStyle(color: AppColors.textSecondary, fontSize: 16)),
                ],
              ),
            ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: FilledButton(
            key: const Key('tutorial_next'),
            onPressed: () {
              if (last) {
                Navigator.of(context).pop();
              } else {
                _pages.nextPage(duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
              }
            },
            child: Text(last ? '完成' : '下一頁'),
          ),
        ),
      ),
    );
  }
}
