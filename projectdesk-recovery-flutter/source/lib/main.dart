import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const RecoveryApp());

class RecoveryApp extends StatelessWidget {
  const RecoveryApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'ProjectDesk Recovery',
      locale: const Locale('ar'),
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF934C40),
          brightness: Brightness.light,
        ),
        scaffoldBackgroundColor: const Color(0xFFFBF9F6),
        useMaterial3: true,
      ),
      home: const Directionality(
        textDirection: TextDirection.rtl,
        child: RecoveryHome(),
      ),
    );
  }
}

class RecoveryHome extends StatefulWidget {
  const RecoveryHome({super.key});

  @override
  State<RecoveryHome> createState() => _RecoveryHomeState();
}

class _RecoveryHomeState extends State<RecoveryHome> {
  static const _channel = MethodChannel('projectdesk.recovery/control');
  Timer? _timer;
  Map<String, dynamic> _state = const {};
  bool _busy = false;
  String _message = '';

  int get _count => (_state['project_count'] as num?)?.toInt() ?? 0;
  bool get _active => _state['active'] == true;
  bool get _serviceEnabled => _state['service_enabled'] == true;
  String get _status => (_state['status'] ?? 'لم يبدأ الاسترجاع بعد.').toString();
  List<dynamic> get _projects => (_state['projects'] as List<dynamic>?) ?? const [];

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(milliseconds: 900), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final raw = await _channel.invokeMethod<Map>('getState');
      if (!mounted || raw == null) return;
      setState(() => _state = Map<String, dynamic>.from(raw));
    } catch (_) {}
  }

  Future<void> _invoke(String method) async {
    try {
      await _channel.invokeMethod(method);
      await _refresh();
    } on PlatformException catch (e) {
      _show(e.message ?? e.code, error: true);
    }
  }

  Future<void> _createBackup() async {
    if (_count <= 0 || _active || _busy) return;
    setState(() {
      _busy = true;
      _message = 'جارٍ إنشاء قاعدة SQLite والتحقق منها…';
    });
    try {
      final raw = await _channel.invokeMethod<Map>('createBackup');
      final result = raw == null ? <String, dynamic>{} : Map<String, dynamic>.from(raw);
      final count = result['project_count'] ?? 0;
      final file = result['file_name'] ?? '';
      setState(() => _message =
          'تم إنشاء نسخة صحيحة تحتوي على $count مشروع.\n$file\nDownloads/ProjectDesk Recovery');
      _show('تم حفظ ملف الاستعادة بنجاح.');
    } on PlatformException catch (e) {
      setState(() => _message = e.message ?? 'فشل إنشاء النسخة.');
      _show(e.message ?? e.code, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _show(String value, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(value, textDirection: TextDirection.rtl),
        backgroundColor: error ? Colors.red.shade800 : null,
      ),
    );
  }

  Widget _card({required Widget child}) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF0EC),
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: const Color(0xFFE9D3CD)),
        boxShadow: const [
          BoxShadow(color: Color(0x12000000), blurRadius: 10, offset: Offset(0, 3)),
        ],
      ),
      child: child,
    );
  }

  @override
  Widget build(BuildContext context) {
    final preview = _projects.take(12).toList();
    return Scaffold(
      appBar: AppBar(
        centerTitle: true,
        title: const Text('ProjectDesk Recovery'),
        backgroundColor: const Color(0xFFF1DDD7),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 20, 18, 36),
          children: [
            _card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('1. تفعيل خدمة الاسترجاع',
                      style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                  const SizedBox(height: 10),
                  Text(
                    _serviceEnabled
                        ? '✓ خدمة الاسترجاع مفعلة.'
                        : 'الخدمة غير مفعلة. يجب تفعيلها أولًا.',
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      color: _serviceEnabled ? Colors.green.shade800 : Colors.red.shade700,
                    ),
                  ),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: () => _invoke('openAccessibility'),
                    icon: const Icon(Icons.accessibility_new),
                    label: const Text('فتح إعدادات إمكانية الوصول'),
                  ),
                ],
              ),
            ),
            _card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('2. اكتشاف المشاريع القديمة',
                      style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                  const SizedBox(height: 10),
                  const Text(
                    'سيتم فتح ProjectDesk القديم وقراءة بطاقات المشاريع كما تظهر: عنوان المشروع، الطالب، والجامعة، ثم تمرير القائمة حتى النهاية.',
                    style: TextStyle(height: 1.7),
                  ),
                  const SizedBox(height: 14),
                  FilledButton.icon(
                    onPressed: (!_active && !_busy)
                        ? () => _invoke('startRecovery')
                        : null,
                    icon: const Icon(Icons.play_arrow_rounded),
                    label: const Text('بدء الاسترجاع'),
                  ),
                  const SizedBox(height: 14),
                  Text(_status, style: const TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 6),
                  Text('عدد المشاريع المكتشفة: $_count',
                      style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
                ],
              ),
            ),
            if (_count > 0)
              _card(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text('المشاريع المكتشفة قبل النسخ',
                        style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800)),
                    const SizedBox(height: 12),
                    ...preview.asMap().entries.map((entry) {
                      final m = Map<String, dynamic>.from(entry.value as Map);
                      final title = (m['title'] ?? '').toString();
                      final student = (m['student'] ?? '').toString();
                      final university = (m['university'] ?? '').toString();
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: Text(
                          '${entry.key + 1}) $title'
                          '${student.isNotEmpty ? '\n   الطالب: $student' : ''}'
                          '${university.isNotEmpty ? '\n   الجامعة: $university' : ''}',
                          style: const TextStyle(height: 1.55),
                        ),
                      );
                    }),
                    if (_projects.length > preview.length)
                      Text('… وبقية المشاريع (${_projects.length - preview.length})'),
                  ],
                ),
              ),
            _card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('3. إنشاء ملف الاستعادة',
                      style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                  const SizedBox(height: 10),
                  const Text(
                    'لن يسمح التطبيق بإنشاء Backup إذا كان عدد المشاريع صفرًا. قبل الحفظ يتم فتح قاعدة project_organizer.db وعدّ السجلات فعليًا للتأكد أنها ليست فارغة.',
                    style: TextStyle(height: 1.7),
                  ),
                  const SizedBox(height: 14),
                  FilledButton.icon(
                    onPressed: (_count > 0 && !_active && !_busy) ? _createBackup : null,
                    icon: _busy
                        ? const SizedBox.square(
                            dimension: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.download_rounded),
                    label: const Text('إنشاء وحفظ نسخة .pobackup'),
                  ),
                  if (_message.isNotEmpty) ...[
                    const SizedBox(height: 14),
                    Text(_message, style: const TextStyle(height: 1.6)),
                  ],
                ],
              ),
            ),
            const Text(
              'مهم: لا تحذف ProjectDesk القديم ولا تمسح بياناته حتى يتم استيراد النسخة في ProjectDesk Next والتأكد من ظهور المشاريع.',
              style: TextStyle(
                color: Color(0xFF9E352C),
                fontWeight: FontWeight.w800,
                height: 1.6,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
