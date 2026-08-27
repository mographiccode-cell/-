import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const VaultApp());
}

class NativeBridge {
  static const _channel = MethodChannel('com.mographiccode.deletedmessagevault/native');

  static Future<bool> notificationAccess() async =>
      (await _channel.invokeMethod<bool>('isNotificationAccessEnabled')) ?? false;

  static Future<void> openNotificationSettings() async =>
      _channel.invokeMethod<void>('openNotificationAccessSettings');

  static Future<Map<String, bool>> mediaPermissions() async {
    final raw = await _channel.invokeMapMethod<String, dynamic>('getMediaPermissionState') ?? {};
    return raw.map((key, value) => MapEntry(key, value == true));
  }

  static Future<Map<String, bool>> requestMediaPermissions() async {
    final raw = await _channel.invokeMapMethod<String, dynamic>('requestMediaPermissions') ?? {};
    return raw.map((key, value) => MapEntry(key, value == true));
  }

  static Future<int> scanRecentMedia() async =>
      (await _channel.invokeMethod<int>('scanRecentMedia')) ?? 0;

  static Future<List<CapturedItem>> messages({String? query, bool deletedOnly = false}) async {
    final raw = await _channel.invokeListMethod<dynamic>('getMessages', {
          'query': query,
          'deletedOnly': deletedOnly,
        }) ??
        [];
    return raw
        .whereType<Map>()
        .map((item) => CapturedItem.fromMap(Map<String, dynamic>.from(item)))
        .toList();
  }

  static Future<AppStats> stats() async {
    final raw = await _channel.invokeMapMethod<String, dynamic>('getStats') ?? {};
    return AppStats(
      total: (raw['total'] as num?)?.toInt() ?? 0,
      deleted: (raw['deleted'] as num?)?.toInt() ?? 0,
      media: (raw['media'] as num?)?.toInt() ?? 0,
      today: (raw['today'] as num?)?.toInt() ?? 0,
    );
  }

  static Future<void> clearData() async => _channel.invokeMethod<void>('clearData');
}

class CapturedItem {
  const CapturedItem({
    required this.id,
    required this.packageName,
    required this.sender,
    required this.body,
    required this.postedAt,
    required this.isDeleted,
    required this.contentType,
    this.mediaPath,
    this.mimeType,
    this.mediaName,
  });

  final int id;
  final String packageName;
  final String sender;
  final String body;
  final DateTime postedAt;
  final bool isDeleted;
  final String contentType;
  final String? mediaPath;
  final String? mimeType;
  final String? mediaName;

  bool get hasMedia => mediaPath != null && mediaPath!.isNotEmpty;
  bool get isImage => hasMedia && (contentType == 'image' || contentType == 'gif' || contentType == 'sticker');

  factory CapturedItem.fromMap(Map<String, dynamic> map) {
    return CapturedItem(
      id: (map['id'] as num?)?.toInt() ?? 0,
      packageName: map['packageName']?.toString() ?? '',
      sender: map['sender']?.toString() ?? 'واتساب',
      body: map['body']?.toString() ?? '',
      postedAt: DateTime.fromMillisecondsSinceEpoch((map['postedAt'] as num?)?.toInt() ?? 0),
      isDeleted: map['isDeleted'] == true,
      contentType: map['contentType']?.toString() ?? 'text',
      mediaPath: map['mediaPath']?.toString(),
      mimeType: map['mimeType']?.toString(),
      mediaName: map['mediaName']?.toString(),
    );
  }
}

class AppStats {
  const AppStats({this.total = 0, this.deleted = 0, this.media = 0, this.today = 0});
  final int total;
  final int deleted;
  final int media;
  final int today;
}

class VaultApp extends StatelessWidget {
  const VaultApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'الرسائل المحفوظة',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF176B5B),
        scaffoldBackgroundColor: const Color(0xFFF6F8F7),
      ),
      home: const Directionality(
        textDirection: TextDirection.rtl,
        child: VaultHome(),
      ),
    );
  }
}

class VaultHome extends StatefulWidget {
  const VaultHome({super.key});

  @override
  State<VaultHome> createState() => _VaultHomeState();
}

class _VaultHomeState extends State<VaultHome> with WidgetsBindingObserver {
  final _searchController = TextEditingController();
  bool _loading = true;
  bool _notificationAccess = false;
  bool _deletedOnly = false;
  bool _syncing = false;
  Map<String, bool> _mediaPermissions = const {};
  AppStats _stats = const AppStats();
  List<CapturedItem> _items = const [];

  bool get _hasVisualMediaPermission =>
      _mediaPermissions['images'] == true || _mediaPermissions['video'] == true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reload();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _searchController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _reload();
  }

  Future<void> _reload() async {
    if (mounted) setState(() => _loading = true);
    try {
      final notification = await NativeBridge.notificationAccess();
      final permissions = await NativeBridge.mediaPermissions();
      final stats = await NativeBridge.stats();
      final items = await NativeBridge.messages(
        query: _searchController.text.trim().isEmpty ? null : _searchController.text.trim(),
        deletedOnly: _deletedOnly,
      );
      if (!mounted) return;
      setState(() {
        _notificationAccess = notification;
        _mediaPermissions = permissions;
        _stats = stats;
        _items = items;
      });
    } on PlatformException catch (error) {
      _showSnack('خطأ: ${error.message ?? error.code}');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _grantMediaAccess() async {
    try {
      final result = await NativeBridge.requestMediaPermissions();
      if (!mounted) return;
      setState(() => _mediaPermissions = result);
      if (result.values.any((value) => value)) await _syncMedia();
    } on PlatformException catch (error) {
      _showSnack('تعذر منح صلاحية الوسائط: ${error.message ?? error.code}');
    }
  }

  Future<void> _syncMedia() async {
    if (_syncing) return;
    setState(() => _syncing = true);
    try {
      final count = await NativeBridge.scanRecentMedia();
      await _reload();
      _showSnack(count > 0 ? 'تم حفظ $count ملف وسائط جديد' : 'لا توجد وسائط واتساب جديدة للحفظ');
    } on PlatformException catch (error) {
      _showSnack('تعذر مزامنة الوسائط: ${error.message ?? error.code}');
    } finally {
      if (mounted) setState(() => _syncing = false);
    }
  }

  void _showSnack(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _clearData() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('حذف كل البيانات؟'),
        content: const Text('سيتم حذف الرسائل والوسائط التي حفظها التطبيق محليًا. لا يمكن التراجع عن ذلك.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('إلغاء')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('حذف')),
        ],
      ),
    );
    if (accepted != true) return;
    await NativeBridge.clearData();
    await _reload();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('الرسائل المحفوظة'),
        actions: [
          IconButton(onPressed: _clearData, icon: const Icon(Icons.delete_outline), tooltip: 'حذف البيانات'),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _reload,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
          children: [
            _setupCard(),
            const SizedBox(height: 12),
            _statsRow(),
            const SizedBox(height: 16),
            TextField(
              controller: _searchController,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _reload(),
              decoration: InputDecoration(
                hintText: 'ابحث باسم المرسل أو نص الرسالة...',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _searchController.text.isEmpty
                    ? null
                    : IconButton(
                        onPressed: () {
                          _searchController.clear();
                          _reload();
                        },
                        icon: const Icon(Icons.close),
                      ),
                filled: true,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(16), borderSide: BorderSide.none),
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                FilterChip(
                  selected: !_deletedOnly,
                  label: const Text('الكل'),
                  onSelected: (_) {
                    setState(() => _deletedOnly = false);
                    _reload();
                  },
                ),
                const SizedBox(width: 8),
                FilterChip(
                  selected: _deletedOnly,
                  label: const Text('المحذوفة بعد الحفظ'),
                  onSelected: (_) {
                    setState(() => _deletedOnly = true);
                    _reload();
                  },
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (_loading)
              const Padding(
                padding: EdgeInsets.all(32),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_items.isEmpty)
              const _EmptyState()
            else
              ..._items.map((item) => Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: _MessageCard(item: item),
                  )),
          ],
        ),
      ),
    );
  }

  Widget _setupCard() {
    final mediaReady = _mediaPermissions.values.any((value) => value);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('إعداد الحماية', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            _statusLine(
              icon: Icons.notifications_active_outlined,
              title: 'حفظ رسائل الإشعارات',
              active: _notificationAccess,
              buttonText: _notificationAccess ? null : 'تفعيل',
              onPressed: _notificationAccess ? null : NativeBridge.openNotificationSettings,
            ),
            const Divider(height: 24),
            _statusLine(
              icon: Icons.photo_library_outlined,
              title: 'حفظ صور وفيديو وصوت واتساب',
              active: mediaReady,
              buttonText: mediaReady ? 'مزامنة الآن' : 'منح الصلاحية',
              onPressed: mediaReady ? _syncMedia : _grantMediaAccess,
            ),
            if (!_hasVisualMediaPermission) ...[
              const SizedBox(height: 10),
              const Text(
                'لأفضل نتيجة اختر السماح الكامل للصور والفيديو. التطبيق ينسخ فقط وسائط WhatsApp التي تظهر عبر MediaStore إلى مساحة خاصة به.',
                style: TextStyle(fontSize: 12.5, height: 1.45),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _statusLine({
    required IconData icon,
    required String title,
    required bool active,
    String? buttonText,
    Future<void> Function()? onPressed,
  }) {
    return Row(
      children: [
        CircleAvatar(child: Icon(icon)),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 3),
              Text(active ? 'مفعّل' : 'غير مفعّل', style: TextStyle(color: active ? Colors.green.shade700 : Colors.orange.shade800)),
            ],
          ),
        ),
        if (buttonText != null)
          FilledButton.tonal(
            onPressed: _syncing ? null : onPressed,
            child: _syncing && buttonText == 'مزامنة الآن'
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(buttonText),
          ),
      ],
    );
  }

  Widget _statsRow() {
    final cards = [
      ('محفوظة', _stats.total, Icons.chat_bubble_outline),
      ('محذوفة', _stats.deleted, Icons.delete_outline),
      ('وسائط', _stats.media, Icons.perm_media_outlined),
      ('اليوم', _stats.today, Icons.today_outlined),
    ];
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: cards
          .map((card) => SizedBox(
                width: (MediaQuery.sizeOf(context).width - 48) / 2,
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Row(
                      children: [
                        Icon(card.$3),
                        const SizedBox(width: 10),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('${card.$2}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                            Text(card.$1),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ))
          .toList(),
    );
  }
}

class _MessageCard extends StatelessWidget {
  const _MessageCard({required this.item});
  final CapturedItem item;

  @override
  Widget build(BuildContext context) {
    final file = item.hasMedia ? File(item.mediaPath!) : null;
    final fileExists = file?.existsSync() == true;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(child: Text(item.sender, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
                if (item.isDeleted)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                    decoration: BoxDecoration(color: Colors.red.shade50, borderRadius: BorderRadius.circular(99)),
                    child: Text('محذوفة بعد الحفظ', style: TextStyle(color: Colors.red.shade800, fontSize: 11.5, fontWeight: FontWeight.w700)),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            if (item.isImage && fileExists)
              GestureDetector(
                onTap: () => showDialog<void>(
                  context: context,
                  builder: (context) => Dialog(
                    child: InteractiveViewer(
                      child: Image.file(file!, fit: BoxFit.contain),
                    ),
                  ),
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(14),
                  child: Image.file(
                    file!,
                    height: 220,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stackTrace) => const _MediaFallback(icon: Icons.broken_image_outlined, text: 'تعذر عرض الصورة المحفوظة'),
                  ),
                ),
              )
            else if (item.hasMedia)
              _MediaFallback(
                icon: item.contentType == 'video'
                    ? Icons.videocam_outlined
                    : item.contentType == 'audio'
                        ? Icons.graphic_eq
                        : Icons.attach_file,
                text: item.mediaName ?? item.body,
              )
            else
              Text(item.body, style: const TextStyle(fontSize: 15, height: 1.45)),
            if (item.hasMedia && item.body.isNotEmpty && !item.body.contains('محفوظ')) ...[
              const SizedBox(height: 8),
              Text(item.body, style: const TextStyle(fontSize: 14, height: 1.4)),
            ],
            const SizedBox(height: 10),
            Text(
              _formatDate(item.postedAt),
              style: TextStyle(color: Colors.grey.shade700, fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  static String _formatDate(DateTime date) {
    final local = date.toLocal();
    String two(int value) => value.toString().padLeft(2, '0');
    return '${two(local.day)}/${two(local.month)}/${local.year}  ${two(local.hour)}:${two(local.minute)}';
  }
}

class _MediaFallback extends StatelessWidget {
  const _MediaFallback({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Icon(icon, size: 32),
          const SizedBox(width: 12),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.symmetric(vertical: 46),
      child: Column(
        children: [
          Icon(Icons.inbox_outlined, size: 54),
          SizedBox(height: 12),
          Text('لا توجد عناصر محفوظة بعد', style: TextStyle(fontWeight: FontWeight.w600)),
          SizedBox(height: 6),
          Text('فعّل الوصول للإشعارات وصلاحية الوسائط ثم استخدم واتساب بصورة طبيعية.'),
        ],
      ),
    );
  }
}
