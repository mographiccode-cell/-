import 'package:flutter_test/flutter_test.dart';
import 'package:social_downloader/main.dart';

void main() {
  test('extracts URL from shared social text', () {
    expect(
      extractFirstUrl('شاهد هذا الفيديو https://youtu.be/abc123 الآن'),
      'https://youtu.be/abc123',
    );
  });

  test('detects common platforms', () {
    expect(detectPlatform('https://www.youtube.com/watch?v=1'), 'YouTube');
    expect(detectPlatform('https://www.instagram.com/reel/1'), 'Instagram');
    expect(detectPlatform('https://x.com/user/status/1'), 'X');
    expect(detectPlatform('https://www.facebook.com/watch/?v=1'), 'Facebook');
  });
}
