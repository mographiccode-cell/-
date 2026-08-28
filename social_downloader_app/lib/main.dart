import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';
import 'package:video_player/video_player.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SocialDownloaderApp());
}

class SocialDownloaderApp extends StatelessWidget {
  const SocialDownloaderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'تحميل الفيديو',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF6C4DFF),
        scaffoldBackgroundColor: const Color(0xFFF7F7FB),
      ),
      builder: (context, child) => Directionality(
        textDirection: TextDirection.rtl,
        child: child ?? const SizedBox.shrink(),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const MethodChannel _channel = MethodChannel('social_downloader/native');

  final TextEditingController _urlController = TextEditingController();
  final LocalHistoryDb _db = LocalHistoryDb();

  int _tabIndex = 0;
  bool _ready = false;
  bool _downloading = false;
  double _progress = 0;
  String _eta = '';
  String _status = 'جاهز';
  String? _activeUrl;
  String? _lastVideoUri;
  String? _lastVideoName;
  List<DownloadItem> _history = const [];

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    await _db.open();
    _channel.setMethodCallHandler(_handleNativeCall);
    await _reloadHistory();

    String? initialShare;
    try {
      initialShare = await _channel.invokeMethod<String>('getInitialShare');
    } catch (_) {
      initialShare = null;
    }

    if (!mounted) return;
    setState(() => _ready = true);

    if (initialShare != null && initialShare.trim().isNotEmpty) {
      await _handleSharedText(initialShare);
    }
  }

  Future<dynamic> _handleNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'onSharedUrl':
        await _handleSharedText(call.arguments?.toString() ?? '');
        break;
      case 'downloadProgress':
        final args = Map<String, dynamic>.from(call.arguments as Map);
        if (!mounted) return;
        setState(() {
          _progress = ((args['progress'] as num?)?.toDouble() ?? 0)
              .clamp(0.0, 100.0)
              .toDouble();
          _eta = args['eta']?.toString() ?? '';
          final line = args['line']?.toString().trim();
          if (line != null && line.isNotEmpty) _status = line;
        });
        break;
    }
  }

  Future<void> _handleSharedText(String text) async {
    final url = extractFirstUrl(text);
    if (url == null) {
      _showMessage('لم أجد رابطًا صالحًا في المشاركة.');
      return;
    }
    _urlController.text = url;
    if (_downloading && _activeUrl == url) return;
    setState(() => _tabIndex = 0);
    await _startDownload(url, fromShare: true);
  }

  Future<Map<String, dynamic>?> _invokeDownload(String url, int id) {
    return _channel.invokeMapMethod<String, dynamic>('download', {
      'url': url,
      'requestId': id.toString(),
    });
  }

  Future<bool> _loginInsideApp(String site) async {
    final label = site == 'instagram' ? 'Instagram' : 'YouTube';
    final shouldLogin = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text('$label يطلب جلسة دخول'),
            content: Text(
              'سيفتح تسجيل الدخول داخل التطبيق نفسه. بعد تسجيل الدخول اضغط «حفظ الجلسة والعودة»، ثم سيعيد التطبيق المحاولة تلقائيًا.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('إلغاء'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('تسجيل الدخول داخل التطبيق'),
              ),
            ],
          ),
        ) ??
        false;
    if (!shouldLogin || !mounted) return false;

    try {
      return await _channel.invokeMethod<bool>('loginSite', {'site': site}) ?? false;
    } catch (_) {
      _showMessage('تعذر فتح تسجيل الدخول داخل التطبيق.');
      return false;
    }
  }

  Future<void> _startDownload(String rawUrl, {bool fromShare = false}) async {
    final url = extractFirstUrl(rawUrl) ?? rawUrl.trim();
    final uri = Uri.tryParse(url);
    if (uri == null || !uri.hasScheme || !(uri.scheme == 'http' || uri.scheme == 'https')) {
      _showMessage('أدخل رابط http أو https صحيحًا.');
      return;
    }
    if (_downloading) {
      _showMessage('يوجد تنزيل جارٍ الآن.');
      return;
    }

    final platform = detectPlatform(url);
    final id = await _db.insert(url: url, platform: platform, status: 'downloading');

    if (!mounted) return;
    setState(() {
      _downloading = true;
      _activeUrl = url;
      _progress = 0;
      _eta = '';
      _status = fromShare ? 'تم استلام الرابط من المشاركة — بدأ التحميل' : 'بدأ التحميل';
    });
    await _reloadHistory();

    String? completedUri;
    String? completedName;

    try {
      Map<String, dynamic>? result = await _invokeDownload(url, id);

      if (result?['success'] != true && result?['needsAuth'] == true) {
        final site = result?['authSite']?.toString();
        if (site == 'instagram' || site == 'youtube') {
          if (mounted && await _loginInsideApp(site!)) {
            if (mounted) {
              setState(() {
                _progress = 0;
                _status = 'تم حفظ الجلسة — إعادة محاولة التحميل...';
              });
            }
            result = await _invokeDownload(url, id);
          }
        }
      }

      if (result?['success'] == true) {
        completedUri = result?['uri']?.toString();
        completedName = result?['fileName']?.toString();
        await _db.finish(
          id,
          status: 'completed',
          fileName: completedName,
          fileUri: completedUri,
        );
        if (mounted) {
          setState(() {
            _progress = 100;
            _status = 'اكتمل التحميل وحُفظ في Download/SocialDownloader';
            _lastVideoUri = completedUri;
            _lastVideoName = completedName;
          });
          _showMessage('تم التحميل بنجاح.');
        }
      } else {
        final error = result?['error']?.toString() ?? 'تعذر تنزيل الرابط.';
        await _db.finish(id, status: 'failed', error: error);
        if (mounted) {
          setState(() => _status = 'فشل التحميل');
          _showMessage(error);
        }
      }
    } on PlatformException catch (e) {
      final error = e.message ?? 'خطأ أثناء التحميل.';
      await _db.finish(id, status: 'failed', error: error);
      _showMessage(error);
    } catch (e) {
      await _db.finish(id, status: 'failed', error: e.toString());
      _showMessage('تعذر إكمال التحميل.');
    } finally {
      if (mounted) {
        setState(() {
          _downloading = false;
          _activeUrl = null;
        });
      }
      await _reloadHistory();
    }

    if (mounted && completedUri != null && completedUri!.isNotEmpty) {
      await _openPlayer(completedUri!, completedName ?? 'الفيديو');
    }
  }

  Future<void> _openPlayer(String uri, String title) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => InAppVideoPlayerPage(uri: uri, title: title),
      ),
    );
  }

  Future<void> _reloadHistory() async {
    final history = await _db.list();
    if (!mounted) return;
    setState(() => _history = history);
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          duration: const Duration(seconds: 5),
        ),
      );
  }

  @override
  void dispose() {
    _urlController.dispose();
    _db.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('تحميل الفيديو'),
        centerTitle: false,
      ),
      body: !_ready
          ? const Center(child: CircularProgressIndicator())
          : IndexedStack(
              index: _tabIndex,
              children: [_buildDownloader(), _buildHistory()],
            ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tabIndex,
        onDestinationSelected: (value) => setState(() => _tabIndex = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.download_rounded), label: 'تحميل'),
          NavigationDestination(icon: Icon(Icons.history_rounded), label: 'السجل'),
        ],
      ),
    );
  }

  Widget _buildDownloader() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 22, 18, 28),
      children: [
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(24),
          ),
          child: const Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.ios_share_rounded, size: 34),
              SizedBox(height: 12),
              Text('شارك الرابط مباشرة', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
              SizedBox(height: 8),
              Text(
                'من YouTube أو Facebook أو Instagram أو X اضغط مشاركة، ثم اختر هذا التطبيق. سيبدأ تنزيل الرابط العام تلقائيًا.',
                style: TextStyle(fontSize: 15, height: 1.6),
              ),
            ],
          ),
        ),
        const SizedBox(height: 22),
        TextField(
          controller: _urlController,
          textDirection: TextDirection.ltr,
          keyboardType: TextInputType.url,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: 'رابط الفيديو',
            hintText: 'https://...',
            prefixIcon: const Icon(Icons.link_rounded),
            suffixIcon: SizedBox(
              width: 96,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  IconButton(
                    tooltip: 'حذف الرابط',
                    onPressed: () {
                      _urlController.clear();
                      FocusScope.of(context).unfocus();
                    },
                    icon: const Icon(Icons.close_rounded),
                  ),
                  IconButton(
                    tooltip: 'لصق',
                    onPressed: () async {
                      final data = await Clipboard.getData(Clipboard.kTextPlain);
                      final text = data?.text ?? '';
                      if (text.isNotEmpty) _urlController.text = text;
                    },
                    icon: const Icon(Icons.content_paste_rounded),
                  ),
                ],
              ),
            ),
            filled: true,
            fillColor: Colors.white,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(18),
              borderSide: BorderSide.none,
            ),
          ),
        ),
        const SizedBox(height: 14),
        FilledButton.icon(
          onPressed: _downloading ? null : () => _startDownload(_urlController.text),
          icon: const Icon(Icons.download_rounded),
          label: Text(_downloading ? 'جارٍ التحميل...' : 'تحميل الآن'),
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(54),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
          ),
        ),
        const SizedBox(height: 22),
        if (_downloading || _progress > 0)
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20)),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _status,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Text('${_progress.toStringAsFixed(0)}%'),
                  ],
                ),
                const SizedBox(height: 12),
                LinearProgressIndicator(value: _progress <= 0 ? null : _progress / 100),
                if (_eta.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text('الوقت المتبقي التقريبي: $_eta ثانية'),
                ],
              ],
            ),
          ),
        if (_lastVideoUri != null) ...[
          const SizedBox(height: 18),
          Card(
            elevation: 0,
            child: ListTile(
              leading: const CircleAvatar(child: Icon(Icons.play_arrow_rounded)),
              title: Text(_lastVideoName ?? 'الفيديو المحمّل'),
              subtitle: const Text('تشغيل الفيديو داخل التطبيق'),
              trailing: const Icon(Icons.chevron_left_rounded),
              onTap: () => _openPlayer(_lastVideoUri!, _lastVideoName ?? 'الفيديو'),
            ),
          ),
        ],
        const SizedBox(height: 18),
        const Text(
          'للمحتوى العام أولًا. إذا طلب Instagram أو YouTube جلسة دخول من الموقع نفسه، يمكن تسجيل الدخول من داخل التطبيق وإعادة المحاولة. لا يتم تجاوز DRM أو المحتوى الخاص.',
          style: TextStyle(fontSize: 13, height: 1.6, color: Colors.black54),
        ),
      ],
    );
  }

  Widget _buildHistory() {
    if (_history.isEmpty) return const Center(child: Text('لا توجد تنزيلات بعد.'));

    return RefreshIndicator(
      onRefresh: _reloadHistory,
      child: ListView.separated(
        padding: const EdgeInsets.all(16),
        itemCount: _history.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          final item = _history[index];
          final completed = item.status == 'completed';
          final failed = item.status == 'failed';
          final playable = completed && (item.fileUri?.isNotEmpty ?? false);
          return Card(
            elevation: 0,
            child: ListTile(
              onTap: playable ? () => _openPlayer(item.fileUri!, item.fileName ?? 'الفيديو') : null,
              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              leading: CircleAvatar(
                child: Icon(
                  completed
                      ? Icons.play_arrow_rounded
                      : failed
                          ? Icons.error_outline_rounded
                          : Icons.downloading_rounded,
                ),
              ),
              title: Text(
                item.fileName?.isNotEmpty == true ? item.fileName! : item.platform,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text(
                '${item.platform} • ${formatDate(item.createdAt)}\n${item.url}',
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                textDirection: TextDirection.ltr,
              ),
              trailing: playable ? const Icon(Icons.play_circle_outline_rounded) : null,
              isThreeLine: true,
            ),
          );
        },
      ),
    );
  }
}

class InAppVideoPlayerPage extends StatefulWidget {
  const InAppVideoPlayerPage({super.key, required this.uri, required this.title});

  final String uri;
  final String title;

  @override
  State<InAppVideoPlayerPage> createState() => _InAppVideoPlayerPageState();
}

class _InAppVideoPlayerPageState extends State<InAppVideoPlayerPage> {
  late final VideoPlayerController _controller;
  bool _ready = false;
  String? _error;
  bool _muted = false;

  @override
  void initState() {
    super.initState();
    _controller = VideoPlayerController.contentUri(Uri.parse(widget.uri));
    _controller.initialize().then((_) {
      if (!mounted) return;
      setState(() => _ready = true);
      _controller.play();
    }).catchError((Object e) {
      if (!mounted) return;
      setState(() => _error = e.toString());
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _seekBy(int seconds) async {
    final position = await _controller.position ?? Duration.zero;
    final duration = _controller.value.duration;
    var target = position + Duration(seconds: seconds);
    if (target < Duration.zero) target = Duration.zero;
    if (target > duration) target = duration;
    await _controller.seekTo(target);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis)),
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Center(
          child: _error != null
              ? Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text('تعذر تشغيل الفيديو داخل التطبيق.\n$_error', style: const TextStyle(color: Colors.white)),
                )
              : !_ready
                  ? const CircularProgressIndicator()
                  : Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Expanded(
                          child: Center(
                            child: AspectRatio(
                              aspectRatio: _controller.value.aspectRatio > 0 ? _controller.value.aspectRatio : 16 / 9,
                              child: Stack(
                                alignment: Alignment.center,
                                children: [
                                  VideoPlayer(_controller),
                                  ValueListenableBuilder<VideoPlayerValue>(
                                    valueListenable: _controller,
                                    builder: (context, value, _) => IconButton.filledTonal(
                                      iconSize: 44,
                                      onPressed: () => value.isPlaying ? _controller.pause() : _controller.play(),
                                      icon: Icon(value.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.fromLTRB(18, 8, 18, 20),
                          child: Column(
                            children: [
                              VideoProgressIndicator(
                                _controller,
                                allowScrubbing: true,
                                padding: const EdgeInsets.symmetric(vertical: 12),
                              ),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  IconButton(
                                    color: Colors.white,
                                    tooltip: 'رجوع 10 ثوانٍ',
                                    onPressed: () => _seekBy(-10),
                                    icon: const Icon(Icons.replay_10_rounded),
                                  ),
                                  const SizedBox(width: 16),
                                  ValueListenableBuilder<VideoPlayerValue>(
                                    valueListenable: _controller,
                                    builder: (context, value, _) => IconButton.filled(
                                      iconSize: 36,
                                      onPressed: () => value.isPlaying ? _controller.pause() : _controller.play(),
                                      icon: Icon(value.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded),
                                    ),
                                  ),
                                  const SizedBox(width: 16),
                                  IconButton(
                                    color: Colors.white,
                                    tooltip: 'تقديم 10 ثوانٍ',
                                    onPressed: () => _seekBy(10),
                                    icon: const Icon(Icons.forward_10_rounded),
                                  ),
                                  const SizedBox(width: 16),
                                  IconButton(
                                    color: Colors.white,
                                    tooltip: _muted ? 'تشغيل الصوت' : 'كتم الصوت',
                                    onPressed: () {
                                      setState(() => _muted = !_muted);
                                      _controller.setVolume(_muted ? 0 : 1);
                                    },
                                    icon: Icon(_muted ? Icons.volume_off_rounded : Icons.volume_up_rounded),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
        ),
      ),
    );
  }
}

String? extractFirstUrl(String text) {
  final match = RegExp(r'https?://\S+', caseSensitive: false).firstMatch(text);
  if (match == null) return null;
  return match.group(0)?.replaceAll(RegExp(r'[),.;]+$'), '');
}

String detectPlatform(String url) {
  final host = Uri.tryParse(url)?.host.toLowerCase() ?? '';
  if (host.contains('youtube.com') || host.contains('youtu.be')) return 'YouTube';
  if (host.contains('instagram.com')) return 'Instagram';
  if (host.contains('facebook.com') || host.contains('fb.watch')) return 'Facebook';
  if (host == 'x.com' || host.endsWith('.x.com') || host.contains('twitter.com')) return 'X';
  if (host.contains('tiktok.com')) return 'TikTok';
  return host.isEmpty ? 'رابط' : host;
}

String formatDate(String value) {
  final parsed = DateTime.tryParse(value)?.toLocal();
  if (parsed == null) return value;
  String two(int n) => n.toString().padLeft(2, '0');
  return '${parsed.year}-${two(parsed.month)}-${two(parsed.day)} ${two(parsed.hour)}:${two(parsed.minute)}';
}

class DownloadItem {
  const DownloadItem({
    required this.id,
    required this.url,
    required this.platform,
    required this.status,
    required this.createdAt,
    this.fileName,
    this.fileUri,
    this.error,
  });

  final int id;
  final String url;
  final String platform;
  final String status;
  final String createdAt;
  final String? fileName;
  final String? fileUri;
  final String? error;

  factory DownloadItem.fromMap(Map<String, Object?> map) {
    return DownloadItem(
      id: map['id'] as int,
      url: map['url'] as String,
      platform: map['platform'] as String,
      status: map['status'] as String,
      createdAt: map['created_at'] as String,
      fileName: map['file_name'] as String?,
      fileUri: map['file_uri'] as String?,
      error: map['error'] as String?,
    );
  }
}

class LocalHistoryDb {
  Database? _database;

  Future<void> open() async {
    final base = await getDatabasesPath();
    _database = await openDatabase(
      p.join(base, 'social_downloader.db'),
      version: 1,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE downloads(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT NOT NULL,
            platform TEXT NOT NULL,
            status TEXT NOT NULL,
            file_name TEXT,
            file_uri TEXT,
            error TEXT,
            created_at TEXT NOT NULL
          )
        ''');
      },
    );
  }

  Future<int> insert({required String url, required String platform, required String status}) async {
    return _database!.insert('downloads', {
      'url': url,
      'platform': platform,
      'status': status,
      'created_at': DateTime.now().toUtc().toIso8601String(),
    });
  }

  Future<void> finish(
    int id, {
    required String status,
    String? fileName,
    String? fileUri,
    String? error,
  }) async {
    await _database!.update(
      'downloads',
      {
        'status': status,
        'file_name': fileName,
        'file_uri': fileUri,
        'error': error,
      },
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<List<DownloadItem>> list() async {
    final rows = await _database!.query('downloads', orderBy: 'id DESC');
    return rows.map(DownloadItem.fromMap).toList(growable: false);
  }

  Future<void> close() async {
    await _database?.close();
  }
}
