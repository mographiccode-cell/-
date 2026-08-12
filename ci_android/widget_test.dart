import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wifi_guard_ai/main.dart';

void main() {
  testWidgets('dashboard renders core security controls and dataset section', (tester) async {
    await tester.pumpWidget(const WifiGuardApp());
    await tester.pumpAndSettle();

    expect(find.text('WiFi Guard AI'), findsOneWidget);
    expect(find.text('DNS Spoofing'), findsOneWidget);
    expect(find.text('MITM'), findsOneWidget);
    expect(find.text('بدء المراقبة'), findsOneWidget);

    await tester.drag(find.byType(ListView), const Offset(0, -650));
    await tester.pumpAndSettle();

    expect(find.text('جمع Dataset من هواتف فعلية'), findsOneWidget);
    expect(find.text('تسجيل Normal'), findsOneWidget);
    expect(find.text('تسجيل DNS Spoofing'), findsOneWidget);
    expect(find.text('تسجيل MITM'), findsOneWidget);
  });
}
