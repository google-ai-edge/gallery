import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:get/get.dart';

import '../controllers/home_controller.dart';
import '../core/theme/app_theme.dart';
import '../models/chat_message_item.dart';
import '../models/native_model_summary.dart';

class HomeView extends GetView<HomeController> {
  const HomeView({super.key});

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      if (controller.bootstrapping.value &&
          controller.inventory.value == null) {
        return const Scaffold(body: Center(child: CircularProgressIndicator()));
      }

      return Scaffold(
        drawer: const _ChatHistoryDrawer(),
        body: Container(
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: <Color>[Color(0xFFF7F8FD), Color(0xFFF1F4FB)],
            ),
          ),
          child: SafeArea(
            child: Column(
              children: <Widget>[
                if ((controller.inventory.value?.operationMessage ?? '')
                    .isNotEmpty)
                  _OperationBanner(
                    message: controller.inventory.value!.operationMessage,
                  ),
                Expanded(
                  child: IndexedStack(
                    index: controller.tabIndex.value,
                    children: const <Widget>[
                      _ChatTab(),
                      _ModelsTab(),
                      _ServerTab(),
                      _SettingsTab(),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        bottomNavigationBar: Obx(
          () => Container(
            decoration: const BoxDecoration(
              color: Colors.white,
              border: Border(
                top: BorderSide(color: Color(0xFFEAECF2), width: 1),
              ),
            ),
            child: NavigationBar(
              selectedIndex: controller.tabIndex.value,
              onDestinationSelected: controller.setTab,
              backgroundColor: Colors.white,
              surfaceTintColor: Colors.transparent,
              elevation: 0,
              indicatorColor: const Color(0xFFE8E6FF),
              destinations: const <NavigationDestination>[
                NavigationDestination(
                  icon: Icon(Icons.chat_bubble_outline_rounded),
                  selectedIcon: Icon(Icons.chat_bubble_rounded),
                  label: 'Chats',
                ),
                NavigationDestination(
                  icon: Icon(Icons.widgets_outlined),
                  selectedIcon: Icon(Icons.widgets_rounded),
                  label: 'Models',
                ),
                NavigationDestination(
                  icon: Icon(Icons.dns_outlined),
                  selectedIcon: Icon(Icons.dns_rounded),
                  label: 'Server',
                ),
                NavigationDestination(
                  icon: Icon(Icons.settings_outlined),
                  selectedIcon: Icon(Icons.settings_rounded),
                  label: 'Settings',
                ),
              ],
            ),
          ),
        ),
      );
    });
  }
}

class _ChatTab extends StatefulWidget {
  const _ChatTab();

  @override
  State<_ChatTab> createState() => _ChatTabState();
}

class _ChatTabState extends State<_ChatTab> {
  final ScrollController _scrollController = ScrollController();
  final HomeController controller = Get.find<HomeController>();
  bool _userScrolledUp = false;
  Worker? _messageWorker;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    // Watch messages list for changes and auto-scroll
    _messageWorker = ever(controller.messages, (_) => _autoScroll());
  }

  @override
  void dispose() {
    _messageWorker?.dispose();
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final maxScroll = _scrollController.position.maxScrollExtent;
    final currentScroll = _scrollController.position.pixels;
    // User is "at bottom" if within 80px of the end
    _userScrolledUp = (maxScroll - currentScroll) > 80;
  }

  void _autoScroll() {
    if (_userScrolledUp || !_scrollController.hasClients) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 100),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
          child: Row(
            children: <Widget>[
              Builder(
                builder: (context) => IconButton(
                  onPressed: () => Scaffold.of(context).openDrawer(),
                  icon: const Icon(Icons.menu_rounded),
                ),
              ),
              const SizedBox(width: 4),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text('Chats', style: theme.textTheme.headlineMedium),
                    const SizedBox(height: 6),
                    Text(
                      controller.activeModelName == null
                          ? 'Load a model to start chatting with the native runtime.'
                          : 'Using ${controller.activeModelName}',
                      style: theme.textTheme.bodyMedium,
                    ),
                  ],
                ),
              ),
              IconButton(
                onPressed: controller.resetConversation,
                icon: const Icon(Icons.edit_square),
              ),
            ],
          ),
        ),
        Expanded(
          child: Obx(() {
            if (controller.messages.isEmpty) {
              return const _EmptyChatState();
            }
            return ListView.separated(
              controller: _scrollController,
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
              itemCount: controller.messages.length,
              separatorBuilder: (_, index) => const SizedBox(height: 12),
              itemBuilder: (context, index) {
                return _ChatBubble(message: controller.messages[index]);
              },
            );
          }),
        ),
        const _ChatComposerPanel(),
      ],
    );
  }
}

class _ModelsTab extends GetView<HomeController> {
  const _ModelsTab();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 10),
          child: Row(
            children: <Widget>[
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text('Models', style: theme.textTheme.headlineMedium),
                    const SizedBox(height: 6),
                    Text(
                      'Download, import, and load native models into memory.',
                      style: theme.textTheme.bodyMedium,
                    ),
                  ],
                ),
              ),
              FilledButton.icon(
                onPressed: () => _showImportSheet(context),
                style: FilledButton.styleFrom(
                  backgroundColor: AppTheme.violet,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 18,
                    vertical: 16,
                  ),
                ),
                icon: const Icon(Icons.add),
                label: const Text('Import'),
              ),
            ],
          ),
        ),
        SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          scrollDirection: Axis.horizontal,
          child: Obx(
            () => Row(
              children: <String>['All', 'Downloaded', 'Media', 'Custom']
                  .map(
                    (filter) => Padding(
                      padding: const EdgeInsets.only(right: 10),
                      child: ChoiceChip(
                        label: Text(filter),
                        selected: controller.modelFilter.value == filter,
                        onSelected: (_) => controller.setModelFilter(filter),
                      ),
                    ),
                  )
                  .toList(),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: Obx(
            () => ListView.separated(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
              itemCount: controller.visibleModels.length,
              separatorBuilder: (_, index) => const SizedBox(height: 16),
              itemBuilder: (context, index) {
                return _ModelCard(model: controller.visibleModels[index]);
              },
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _showImportSheet(BuildContext context) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => const _ImportModelSheet(),
    );
  }
}

class _ServerTab extends GetView<HomeController> {
  const _ServerTab();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Obx(() {
      final server = controller.serverStatus.value;
      final tunnelIsStarting =
          (server?.isRunning ?? false) &&
          (server?.tunnelEnabled ?? controller.useTunnel.value) &&
          (server?.publicUrl?.isNotEmpty ?? false) == false;
      final externalTestDisabled =
          controller.externalServerTestInProgress.value ||
          (server?.publicUrl?.isNotEmpty ?? false) == false;
      return ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        children: <Widget>[
          Text('Server', style: theme.textTheme.headlineMedium),
          const SizedBox(height: 6),
          Text(
            'Control the native OpenAI-compatible server exposed from the Android runtime.',
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 18),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(22),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Row(
                    children: <Widget>[
                      Container(
                        width: 12,
                        height: 12,
                        decoration: BoxDecoration(
                          color: (server?.isRunning ?? false)
                              ? AppTheme.mint
                              : const Color(0xFFD1D7E8),
                          shape: BoxShape.circle,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Text(
                        (server?.isRunning ?? false)
                            ? 'Server running'
                            : 'Server stopped',
                        style: theme.textTheme.titleMedium,
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  SwitchListTile(
                    value: controller.useTunnel.value,
                    onChanged: (value) => controller.useTunnel.value = value,
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Enable tunnel'),
                    subtitle: const Text(
                      'Expose the server with a public URL.',
                    ),
                  ),
                  _ServerField(
                    label: 'Local URL',
                    value: server?.localUrl ?? 'Not available',
                  ),
                  _ServerField(
                    label: 'Public URL',
                    value:
                        server?.publicUrl ??
                        (tunnelIsStarting
                            ? 'Starting tunnel, connecting to Cloudflare...'
                            : 'Tunnel disabled'),
                  ),
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: controller.serverActionInProgress.value
                          ? null
                          : (server?.isRunning ?? false)
                          ? controller.stopServer
                          : controller.startServer,
                      style: FilledButton.styleFrom(
                        backgroundColor: (server?.isRunning ?? false)
                            ? AppTheme.coral
                            : AppTheme.violet,
                        padding: const EdgeInsets.symmetric(vertical: 18),
                      ),
                      child: controller.serverActionInProgress.value
                          ? _DotsLoader(
                              label: (server?.isRunning ?? false)
                                  ? 'Stopping server'
                                  : 'Starting server',
                            )
                          : Text(
                              (server?.isRunning ?? false)
                                  ? 'Stop server'
                                  : 'Start server',
                            ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: controller.localServerTestInProgress.value
                          ? null
                          : controller.testLocalServerHealth,
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                      ),
                      icon: controller.localServerTestInProgress.value
                          ? const Icon(Icons.sync_rounded)
                          : const Icon(Icons.lan_rounded),
                      label: Text(
                        controller.localServerTestInProgress.value
                            ? 'Testing local health...'
                            : 'Test local /health',
                      ),
                    ),
                  ),
                  if (controller
                      .localServerTestMessage
                      .value
                      .isNotEmpty) ...<Widget>[
                    const SizedBox(height: 14),
                    _StatusMessageCard(
                      success: controller.localServerTestSucceeded.value,
                      message: controller.localServerTestMessage.value,
                    ),
                  ],
                  const SizedBox(height: 12),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: externalTestDisabled
                          ? null
                          : controller.testExternalServerHealth,
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                      ),
                      icon: controller.externalServerTestInProgress.value
                          ? const Icon(Icons.sync_rounded)
                          : const Icon(Icons.public_rounded),
                      label: Text(
                        controller.externalServerTestInProgress.value
                            ? 'Testing external health...'
                            : 'Test external /health',
                      ),
                    ),
                  ),
                  if (controller
                      .externalServerTestMessage
                      .value
                      .isNotEmpty) ...<Widget>[
                    const SizedBox(height: 14),
                    _StatusMessageCard(
                      success: controller.externalServerTestSucceeded.value,
                      message: controller.externalServerTestMessage.value,
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 18),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(22),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text('Endpoint examples', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Text(
                    'You can call this server from another app on the phone, another device on the same network, or a computer using the tunnel URL.',
                    style: theme.textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 16),
                  _CodeExampleCard(
                    title: 'List models',
                    code: _curlModelsExample(
                      (server?.publicUrl?.isNotEmpty ?? false)
                          ? server!.publicUrl
                          : server?.localUrl,
                    ),
                  ),
                  const SizedBox(height: 12),
                  _CodeExampleCard(
                    title: 'Chat completions',
                    code: _curlChatExample(
                      (server?.publicUrl?.isNotEmpty ?? false)
                          ? server!.publicUrl
                          : server?.localUrl,
                    ),
                  ),
                  const SizedBox(height: 12),
                  _CodeExampleCard(
                    title: 'OpenAI compatible client',
                    code: _pythonClientExample(
                      (server?.publicUrl?.isNotEmpty ?? false)
                          ? server!.publicUrl
                          : server?.localUrl,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      );
    });
  }
}

class _SettingsTab extends GetView<HomeController> {
  const _SettingsTab();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final systemPromptController = TextEditingController(
      text: controller.systemPrompt.value,
    );
    final temperatureController = TextEditingController(
      text: controller.temperature.value.toStringAsFixed(2),
    );
    final maxTokensController = TextEditingController(
      text: controller.maxTokens.value.toString(),
    );
    final tokenController = TextEditingController(
      text: controller.huggingFaceToken.value,
    );

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      children: <Widget>[
        Text('Settings', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 6),
        Text(
          'Control Flutter chat defaults, the Hugging Face token used for gated models, and the system behavior used by the native runtime bridge.',
          style: theme.textTheme.bodyMedium,
        ),
        const SizedBox(height: 18),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(22),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text('Runtime defaults', style: theme.textTheme.titleMedium),
                const SizedBox(height: 16),
                TextField(
                  controller: temperatureController,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: const InputDecoration(
                    labelText: 'Temperature',
                    helperText: 'Lower is steadier, higher is more creative.',
                  ),
                ),
                const SizedBox(height: 14),
                TextField(
                  controller: maxTokensController,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Max tokens',
                    helperText: 'Used by Flutter chat requests.',
                  ),
                ),
                const SizedBox(height: 14),
                TextField(
                  controller: systemPromptController,
                  minLines: 4,
                  maxLines: 8,
                  decoration: const InputDecoration(
                    labelText: 'System prompt',
                    alignLabelWithHint: true,
                    helperText: 'Applied before each Flutter chat request.',
                  ),
                ),
                const SizedBox(height: 14),
                TextField(
                  controller: tokenController,
                  decoration: const InputDecoration(
                    labelText: 'Hugging Face token',
                    helperText:
                        'Needed for gated model downloads. Leave blank to clear it.',
                  ),
                ),
                const SizedBox(height: 18),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: () => controller.saveSettings(
                      nextSystemPrompt: systemPromptController.text,
                      nextTemperature:
                          double.tryParse(temperatureController.text.trim()) ??
                          controller.temperature.value,
                      nextMaxTokens:
                          int.tryParse(maxTokensController.text.trim()) ??
                          controller.maxTokens.value,
                      nextHuggingFaceToken: tokenController.text,
                    ),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppTheme.violet,
                      padding: const EdgeInsets.symmetric(vertical: 18),
                    ),
                    child: const Text('Save settings'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _ChatHistoryDrawer extends GetView<HomeController> {
  const _ChatHistoryDrawer();

  @override
  Widget build(BuildContext context) {
    return Drawer(
      backgroundColor: const Color(0xFFF7F8FC),
      child: SafeArea(
        child: Obx(
          () => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 24, 20, 16),
                child: Row(
                  children: <Widget>[
                    Expanded(
                      child: Text(
                        'Chat History',
                        style: Theme.of(context).textTheme.headlineSmall
                            ?.copyWith(
                              fontWeight: FontWeight.w800,
                              color: AppTheme.ink,
                            ),
                      ),
                    ),
                    FilledButton.icon(
                      onPressed: () {
                        Navigator.of(context).pop();
                        controller.resetConversation();
                      },
                      style: FilledButton.styleFrom(
                        backgroundColor: AppTheme.violet,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 12,
                        ),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(20),
                        ),
                        elevation: 0,
                      ),
                      icon: const Icon(Icons.add_rounded, size: 20),
                      label: const Text(
                        'New',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Expanded(
                child: ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  itemCount: controller.chatSessions.length,
                  separatorBuilder: (_, index) => const SizedBox(height: 8),
                  itemBuilder: (context, index) {
                    final session = controller.chatSessions[index];
                    final selected =
                        controller.currentChatId.value == session.id;

                    return InkWell(
                      onTap: () {
                        Navigator.of(context).pop();
                        controller.openChatSession(session.id);
                      },
                      borderRadius: BorderRadius.circular(20),
                      child: Container(
                        padding: const EdgeInsets.fromLTRB(16, 14, 12, 14),
                        decoration: BoxDecoration(
                          color: selected
                              ? AppTheme.violet.withAlpha(20)
                              : Colors.white,
                          borderRadius: BorderRadius.circular(20),
                          border: Border.all(
                            color: selected
                                ? AppTheme.violet.withAlpha(50)
                                : const Color(0xFFE8EAF2),
                            width: selected ? 1.5 : 1.0,
                          ),
                          boxShadow: selected
                              ? null
                              : <BoxShadow>[
                                  BoxShadow(
                                    color: Colors.black.withAlpha(5),
                                    blurRadius: 8,
                                    offset: const Offset(0, 2),
                                  ),
                                ],
                        ),
                        child: Row(
                          children: <Widget>[
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: <Widget>[
                                  Text(
                                    session.title,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontWeight: selected
                                          ? FontWeight.w700
                                          : FontWeight.w600,
                                      color: selected
                                          ? AppTheme.violet
                                          : AppTheme.ink,
                                      fontSize: 15,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    session.modelName?.isNotEmpty == true
                                        ? session.modelName!
                                        : _formatChatDate(session.updatedAt),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: selected
                                          ? AppTheme.violet.withAlpha(180)
                                          : AppTheme.slate,
                                      fontSize: 12,
                                      fontWeight: FontWeight.w500,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const SizedBox(width: 8),
                            IconButton(
                              onPressed: () =>
                                  controller.deleteChatSession(session.id),
                              style: IconButton.styleFrom(
                                backgroundColor: selected
                                    ? Colors.white.withAlpha(150)
                                    : const Color(0xFFF7F8FC),
                                padding: const EdgeInsets.all(8),
                              ),
                              icon: const Icon(
                                Icons.delete_outline_rounded,
                                size: 18,
                                color: AppTheme.coral,
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CodeExampleCard extends StatelessWidget {
  const _CodeExampleCard({required this.title, required this.code});

  final String title;
  final String code;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF1F2127),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            title,
            style: const TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 10),
          SelectableText(
            code,
            style: const TextStyle(
              color: Color(0xFFD7DCE8),
              fontFamily: 'monospace',
              height: 1.45,
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusMessageCard extends StatelessWidget {
  const _StatusMessageCard({required this.success, required this.message});

  final bool success;
  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: success ? const Color(0xFFEAF8F0) : const Color(0xFFFFF1EF),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: success ? const Color(0xFFC7EBD7) : const Color(0xFFF3C7C3),
        ),
      ),
      child: Text(
        message,
        style: TextStyle(
          color: success ? const Color(0xFF19794B) : AppTheme.coral,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _OperationBanner extends StatelessWidget {
  const _OperationBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 10, 16, 0),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF7E6),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFFF3D79C)),
      ),
      child: Row(
        children: <Widget>[
          const Icon(Icons.hourglass_top_rounded, color: AppTheme.amber),
          const SizedBox(width: 12),
          Expanded(child: Text(message)),
        ],
      ),
    );
  }
}

class _EmptyChatState extends StatelessWidget {
  const _EmptyChatState();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 36),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Container(
              width: 82,
              height: 82,
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: <Color>[Color(0xFF5A58E8), Color(0xFF8F70FF)],
                ),
                borderRadius: BorderRadius.circular(28),
              ),
              child: const Icon(
                Icons.bolt_rounded,
                color: Colors.white,
                size: 34,
              ),
            ),
            const SizedBox(height: 24),
            Text(
              'How can I help you?',
              style: Theme.of(
                context,
              ).textTheme.headlineMedium?.copyWith(fontSize: 26),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'Select and load a native model first, then chat with text, images, documents, or voice.',
              style: Theme.of(
                context,
              ).textTheme.bodyLarge?.copyWith(color: AppTheme.slate),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

class _AttachmentTray extends GetView<HomeController> {
  const _AttachmentTray();

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final hasAnything =
          controller.attachedImagePaths.isNotEmpty ||
          controller.attachedAudioPaths.isNotEmpty ||
          controller.hasAttachedDocument;
      if (!hasAnything) {
        return const SizedBox.shrink();
      }
      return Wrap(
        spacing: 10,
        runSpacing: 10,
        children: <Widget>[
          if (controller.attachedImagePaths.isNotEmpty)
            _AttachmentChip(
              label: controller.attachedImagePaths.length == 1
                  ? _basename(controller.attachedImagePaths.first)
                  : '${controller.attachedImagePaths.length} images attached',
              subtitle: controller.attachedImagePaths.length == 1
                  ? 'Ready to analyze'
                  : 'Gallery bundle ready',
              icon: Icons.image_outlined,
              accent: const Color(0xFF77C0FF),
              previewPath: controller.attachedImagePaths.first,
              onDeleted: controller.clearImages,
            ),
          if (controller.attachedAudioPaths.isNotEmpty)
            _AttachmentChip(
              label: controller.attachedAudioPaths.length == 1
                  ? _basename(controller.attachedAudioPaths.first)
                  : '${controller.attachedAudioPaths.length} recordings attached',
              subtitle: controller.isRecording.value
                  ? 'Listening now'
                  : 'Voice context ready',
              icon: Icons.graphic_eq_rounded,
              accent: const Color(0xFFFFA56E),
              onDeleted: controller.clearAudio,
            ),
          if (controller.hasAttachedDocument)
            _AttachmentChip(
              label: controller.attachedDocumentName.value,
              subtitle: 'Document context ready',
              icon: Icons.description_outlined,
              accent: const Color(0xFF8FD2A9),
              onDeleted: controller.clearDocument,
            ),
        ],
      );
    });
  }
}

class _ChatComposerPanel extends GetView<HomeController> {
  const _ChatComposerPanel();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: const Color(0xFFF7F8FD),
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          const _AttachmentTray(),
          const SizedBox(height: 6),
          Container(
            padding: const EdgeInsets.fromLTRB(6, 4, 6, 4),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(28),
              border: Border.all(color: const Color(0xFFE0E3EB)),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: <Widget>[
                // + button for attachments
                GestureDetector(
                  onTap: () => _showAttachMenu(context),
                  child: Container(
                    width: 36,
                    height: 36,
                    decoration: const BoxDecoration(
                      color: Color(0xFFF3F4F8),
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(
                      Icons.add,
                      color: Color(0xFF5B6478),
                      size: 20,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                // Text field
                Expanded(
                  child: TextField(
                    controller: controller.inputController,
                    onChanged: controller.updateComposer,
                    minLines: 1,
                    maxLines: 4,
                    textInputAction: TextInputAction.newline,
                    style: const TextStyle(
                      color: Color(0xFF1A2035),
                      fontSize: 15,
                      height: 1.35,
                    ),
                    decoration: const InputDecoration(
                      hintText: 'Ask anything...',
                      hintStyle: TextStyle(
                        color: Color(0xFFB0B7C8),
                        fontSize: 15,
                        fontWeight: FontWeight.w400,
                      ),
                      border: InputBorder.none,
                      enabledBorder: InputBorder.none,
                      focusedBorder: InputBorder.none,
                      isCollapsed: true,
                      contentPadding: EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                // Action button
                Obx(() {
                  final hasText = controller.composerText.value
                      .trim()
                      .isNotEmpty;
                  final inProgress = controller.chatInProgress.value;
                  final isRecording = controller.isRecording.value;

                  if (inProgress) {
                    return _ActionCircle(
                      icon: Icons.stop_rounded,
                      onTap: controller.stopGeneration,
                    );
                  }
                  if (hasText) {
                    return _ActionCircle(
                      icon: Icons.arrow_upward_rounded,
                      onTap: controller.sendMessage,
                    );
                  }
                  return _ActionCircle(
                    icon: isRecording ? Icons.stop_rounded : Icons.mic_rounded,
                    onTap: controller.toggleRecording,
                    highlighted: isRecording,
                  );
                }),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _showAttachMenu(BuildContext context) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              ListTile(
                leading: const Icon(Icons.image_outlined),
                title: const Text('Add Images'),
                onTap: () {
                  Navigator.pop(context);
                  controller.pickImages();
                },
              ),
              ListTile(
                leading: const Icon(Icons.description_outlined),
                title: const Text('Add Document'),
                onTap: () {
                  Navigator.pop(context);
                  controller.pickDocument();
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ActionCircle extends StatelessWidget {
  const _ActionCircle({
    required this.icon,
    required this.onTap,
    this.highlighted = false,
  });

  final IconData icon;
  final VoidCallback onTap;
  final bool highlighted;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: highlighted ? const Color(0xFFEF5350) : AppTheme.violet,
          shape: BoxShape.circle,
        ),
        child: Icon(icon, color: Colors.white, size: 20),
      ),
    );
  }
}

class _AttachmentChip extends StatelessWidget {
  const _AttachmentChip({
    required this.label,
    required this.subtitle,
    required this.icon,
    required this.accent,
    required this.onDeleted,
    this.previewPath,
  });

  final String label;
  final String subtitle;
  final IconData icon;
  final Color accent;
  final VoidCallback onDeleted;
  final String? previewPath;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 188,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF343842),
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: const Color(0xFF454A56)),
      ),
      child: Row(
        children: <Widget>[
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: accent.withAlpha(38),
              borderRadius: BorderRadius.circular(14),
            ),
            child: previewPath != null
                ? ClipRRect(
                    borderRadius: BorderRadius.circular(14),
                    child: Image.file(File(previewPath!), fit: BoxFit.cover),
                  )
                : Icon(icon, color: accent, size: 24),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFFADB3C2),
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 6),
          InkWell(
            onTap: onDeleted,
            borderRadius: BorderRadius.circular(12),
            child: Container(
              width: 30,
              height: 30,
              decoration: BoxDecoration(
                color: const Color(0xFF2A2D34),
                borderRadius: BorderRadius.circular(12),
              ),
              child: const Icon(
                Icons.close_rounded,
                color: Colors.white70,
                size: 18,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ChatBubble extends StatefulWidget {
  const _ChatBubble({required this.message});

  final ChatMessageItem message;

  @override
  State<_ChatBubble> createState() => _ChatBubbleState();
}

class _ChatBubbleState extends State<_ChatBubble> {
  @override
  Widget build(BuildContext context) {
    final controller = Get.find<HomeController>();
    final message = widget.message;
    final isUser = message.role == 'user';
    final thinkingText = message.thinking.trim();
    final showThinkingUi =
        !isUser && message.isStreaming && thinkingText.isNotEmpty;
    final visibleText =
        !isUser && message.isStreaming && controller.activeModelSupportsThinking
        ? ''
        : message.text.trim();
    final bubbleColor = isUser ? AppTheme.violet : Colors.white;
    final textColor = isUser ? Colors.white : AppTheme.ink;

    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 320),
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: bubbleColor,
            borderRadius: BorderRadius.circular(24),
            border: isUser ? null : Border.all(color: const Color(0xFFE4E7F0)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              if (message.documentName != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    'Document: ${message.documentName}',
                    style: TextStyle(
                      color: isUser ? Colors.white70 : AppTheme.slate,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              if (message.imagePaths.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: message.imagePaths.take(3).map((path) {
                      return ClipRRect(
                        borderRadius: BorderRadius.circular(16),
                        child: Image.file(
                          File(path),
                          width: 72,
                          height: 72,
                          fit: BoxFit.cover,
                        ),
                      );
                    }).toList(),
                  ),
                ),
              if (message.audioPaths.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    '${message.audioPaths.length} voice attachment(s)',
                    style: TextStyle(
                      color: isUser ? Colors.white70 : AppTheme.slate,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              // Thinking section — collapsible, collapsed by default
              if (showThinkingUi)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 8,
                        ),
                        decoration: BoxDecoration(
                          color: const Color(0xFFF5F0E3),
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: const <Widget>[
                            Icon(
                              Icons.psychology_rounded,
                              size: 16,
                              color: Color(0xFFA08832),
                            ),
                            SizedBox(width: 8),
                            Text(
                              'Thinking',
                              style: TextStyle(
                                color: Color(0xFFA08832),
                                fontSize: 12,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Container(
                        margin: const EdgeInsets.only(top: 8),
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: const Color(0xFFFAF6EC),
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(color: const Color(0xFFE8DFC6)),
                        ),
                        child: Text(
                          thinkingText,
                          style: const TextStyle(
                            color: Color(0xFF8A6A11),
                            fontSize: 12,
                            fontStyle: FontStyle.italic,
                            height: 1.4,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              visibleText.isEmpty && message.isStreaming
                  ? Text(
                      'Thinking...',
                      style: TextStyle(
                        color: textColor,
                        height: 1.4,
                        fontWeight: FontWeight.w600,
                      ),
                    )
                  : MarkdownBody(
                      data: visibleText,
                      selectable: true,
                      styleSheet: MarkdownStyleSheet(
                        p: TextStyle(
                          color: message.isError ? AppTheme.coral : textColor,
                          height: 1.4,
                          fontWeight: FontWeight.w600,
                        ),
                        code: TextStyle(
                          color: isUser ? Colors.white : AppTheme.ink,
                          backgroundColor: isUser
                              ? Colors.white.withAlpha(20)
                              : const Color(0xFFF3F4F8),
                          fontFamily: 'monospace',
                          fontSize: 13,
                        ),
                        codeblockDecoration: BoxDecoration(
                          color: isUser
                              ? Colors.white.withAlpha(20)
                              : const Color(0xFFF3F4F8),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        listBullet: TextStyle(
                          color: message.isError ? AppTheme.coral : textColor,
                        ),
                      ),
                    ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ModelCard extends GetView<HomeController> {
  const _ModelCard({required this.model});

  final NativeModelSummary model;

  @override
  Widget build(BuildContext context) {
    final title = model.displayName.isEmpty ? model.name : model.displayName;
    final isDownloadPending = controller.isDownloadPending(model.name);
    final isLoadPending = controller.isLoadPending(model.name);
    final isUnloadPending = controller.isUnloadPending(model.name);

    return Card(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(28),
        side: BorderSide(
          color: model.isActive
              ? const Color(0xFFE6D28C)
              : const Color(0xFFE7EAF2),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                if (model.imported)
                  const _Badge(
                    label: 'LOCAL',
                    color: Color(0xFFE9F8EF),
                    textColor: AppTheme.mint,
                  ),
                if (model.isCustomRemote)
                  const _Badge(
                    label: 'CUSTOM',
                    color: Color(0xFFEDEBFF),
                    textColor: AppTheme.violet,
                  ),
                if (model.supportsImage)
                  const _Badge(
                    label: 'IMAGE',
                    color: Color(0xFFEFF7FF),
                    textColor: Color(0xFF2E7CC5),
                  ),
                if (model.supportsAudio)
                  const _Badge(
                    label: 'VOICE',
                    color: Color(0xFFFFF0EA),
                    textColor: Color(0xFFCF6A36),
                  ),
                if (model.supportsThinking)
                  const _Badge(
                    label: 'THINKING',
                    color: Color(0xFFFFF6DB),
                    textColor: Color(0xFF9E7A16),
                  ),
                if (model.isActive)
                  const _Badge(
                    label: 'LOADED',
                    color: Color(0xFFE9F8EF),
                    textColor: AppTheme.mint,
                  ),
              ],
            ),
            const SizedBox(height: 18),
            Text(
              title,
              style: Theme.of(
                context,
              ).textTheme.titleLarge?.copyWith(fontSize: 18),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 14,
              runSpacing: 8,
              children: <Widget>[
                _MetaText(label: _humanBytes(model.sizeInBytes)),
                _MetaText(
                  label: model.minRamGb == null
                      ? 'RAM unknown'
                      : 'Min ${model.minRamGb} GB RAM',
                ),
                _MetaText(
                  label: _statusLabel(model),
                  color: model.isDownloaded || model.isActive
                      ? AppTheme.mint
                      : model.isPartiallyDownloaded
                      ? AppTheme.amber
                      : AppTheme.slate,
                ),
              ],
            ),
            if (model.isDownloading || model.isPartiallyDownloaded) ...<Widget>[
              const SizedBox(height: 12),
              Row(
                children: <Widget>[
                  Text(
                    '${(model.progress * 100).round()}%',
                    style: const TextStyle(
                      color: AppTheme.amber,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Text(
                      '${_humanBytes(model.downloadedBytes)} of ${_humanBytes(model.totalBytes)}',
                      style: const TextStyle(
                        color: AppTheme.slate,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      model.isPartiallyDownloaded
                          ? 'Paused'
                          : model.bytesPerSecond > 0
                          ? '${_humanBytes(model.bytesPerSecond)}/s'
                          : 'Calculating speed...',
                      style: const TextStyle(color: AppTheme.slate),
                    ),
                  ),
                  Text(
                    model.isPartiallyDownloaded
                        ? 'Resume anytime'
                        : model.remainingMs > 0
                        ? _formatRemaining(model.remainingMs)
                        : 'Estimating time left...',
                    style: const TextStyle(color: AppTheme.slate),
                  ),
                ],
              ),
            ],
            if (model.isDownloading ||
                model.isInitializing ||
                model.isActive) ...<Widget>[
              const SizedBox(height: 18),
              ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: LinearProgressIndicator(
                  minHeight: 8,
                  value: model.isDownloaded && model.isInitializing
                      ? null
                      : model.progress,
                  backgroundColor: const Color(0xFFE8EAF0),
                  color: model.isInitializing
                      ? AppTheme.amber
                      : AppTheme.violet,
                ),
              ),
            ],
            if (model.downloadError.isNotEmpty ||
                model.initializationError.isNotEmpty) ...<Widget>[
              const SizedBox(height: 12),
              Text(
                model.downloadError.isNotEmpty
                    ? model.downloadError
                    : model.initializationError,
                style: const TextStyle(
                  color: AppTheme.coral,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
            const SizedBox(height: 18),
            Row(
              children: <Widget>[
                Expanded(
                  child: _primaryAction(
                    isDownloadPending: isDownloadPending,
                    isLoadPending: isLoadPending,
                    isUnloadPending: isUnloadPending,
                  ),
                ),
                if (model.isDownloaded && !model.isInitializing) ...<Widget>[
                  const SizedBox(width: 12),
                  IconButton(
                    onPressed: () => controller.deleteModel(model),
                    icon: const Icon(Icons.delete_outline_rounded),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _primaryAction({
    required bool isDownloadPending,
    required bool isLoadPending,
    required bool isUnloadPending,
  }) {
    final activeDownload = controller.activeDownloadingModelName;
    final anotherDownloadActive =
        activeDownload != null &&
        activeDownload != model.name &&
        !model.isDownloading &&
        !isDownloadPending;
    if (isDownloadPending) {
      return FilledButton(
        onPressed: null,
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.violet,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        child: const _DotsLoader(label: 'Starting'),
      );
    }
    if (model.isDownloading) {
      return FilledButton.icon(
        onPressed: () => controller.cancelDownload(model.name),
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.coral,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        icon: const Icon(Icons.stop_circle_outlined),
        label: const Text('Stop'),
      );
    }
    if (model.isInitializing) {
      return FilledButton(
        onPressed: null,
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.amber,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        child: const _DotsLoader(label: 'Loading model'),
      );
    }
    if (model.isPartiallyDownloaded) {
      if (anotherDownloadActive) {
        return FilledButton(
          onPressed: null,
          style: FilledButton.styleFrom(
            backgroundColor: const Color(0xFFD7DCEA),
            foregroundColor: AppTheme.slate,
            padding: const EdgeInsets.symmetric(vertical: 18),
          ),
          child: const Text('Another model is downloading'),
        );
      }
      return FilledButton.icon(
        onPressed: () => controller.downloadModel(model.name),
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.amber,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        icon: const Icon(Icons.download_for_offline_rounded),
        label: const Text('Resume Download'),
      );
    }
    if (!model.isDownloaded) {
      if (anotherDownloadActive) {
        return FilledButton(
          onPressed: null,
          style: FilledButton.styleFrom(
            backgroundColor: const Color(0xFFD7DCEA),
            foregroundColor: AppTheme.slate,
            padding: const EdgeInsets.symmetric(vertical: 18),
          ),
          child: const Text('Another model is downloading'),
        );
      }
      return FilledButton.icon(
        onPressed: () => controller.downloadModel(model.name),
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.violet,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        icon: const Icon(Icons.download_rounded),
        label: Text('Download (${_humanBytes(model.sizeInBytes)})'),
      );
    }
    if (isUnloadPending) {
      return FilledButton(
        onPressed: null,
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.coral,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        child: const _DotsLoader(label: 'Unloading'),
      );
    }
    if (model.isActive) {
      return FilledButton.icon(
        onPressed: () => controller.unloadModel(model.name),
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.coral,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        icon: const Icon(Icons.power_settings_new_rounded),
        label: const Text('Unload'),
      );
    }
    if (isLoadPending) {
      return FilledButton(
        onPressed: null,
        style: FilledButton.styleFrom(
          backgroundColor: AppTheme.mint,
          padding: const EdgeInsets.symmetric(vertical: 18),
        ),
        child: const _DotsLoader(label: 'Loading'),
      );
    }
    return FilledButton.icon(
      onPressed: () => controller.loadModel(model.name),
      style: FilledButton.styleFrom(
        backgroundColor: AppTheme.mint,
        padding: const EdgeInsets.symmetric(vertical: 18),
      ),
      icon: const Icon(Icons.play_arrow_rounded),
      label: const Text('Load Model'),
    );
  }

  static String _statusLabel(NativeModelSummary model) {
    if (model.isActive) {
      return 'Ready';
    }
    if (model.isInitializing) {
      return 'Loading into memory';
    }
    if (model.isDownloading) {
      return 'Downloading';
    }
    if (model.isPartiallyDownloaded) {
      return 'Paused download';
    }
    if (model.isDownloaded) {
      return 'Downloaded';
    }
    return 'Not downloaded';
  }
}

class _Badge extends StatelessWidget {
  const _Badge({
    required this.label,
    required this.color,
    required this.textColor,
  });

  final String label;
  final Color color;
  final Color textColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: textColor,
          fontSize: 12,
          fontWeight: FontWeight.w800,
          letterSpacing: 0.4,
        ),
      ),
    );
  }
}

class _DotsLoader extends StatefulWidget {
  const _DotsLoader({required this.label});

  final String label;

  @override
  State<_DotsLoader> createState() => _DotsLoaderState();
}

class _DotsLoaderState extends State<_DotsLoader> {
  int dots = 1;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(milliseconds: 450), (_) {
      setState(() {
        dots = dots == 3 ? 1 : dots + 1;
      });
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Text('${widget.label}${'.' * dots}');
  }
}

class _MetaText extends StatelessWidget {
  const _MetaText({required this.label, this.color = AppTheme.slate});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Text(
      label,
      style: TextStyle(color: color, fontWeight: FontWeight.w700),
    );
  }
}

class _ServerField extends StatelessWidget {
  const _ServerField({required this.label, required this.value});

  final String label;
  final String value;

  bool get _isCopyable =>
      value.isNotEmpty &&
      value != 'Not available' &&
      value != 'Tunnel disabled' &&
      !value.startsWith('Starting tunnel');

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(label, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 6),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(14, 14, 8, 14),
            decoration: BoxDecoration(
              color: const Color(0xFFF7F8FC),
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: const Color(0xFFE2E6F0)),
            ),
            child: Row(
              children: <Widget>[
                Expanded(child: Text(value)),
                if (_isCopyable)
                  GestureDetector(
                    onTap: () {
                      Clipboard.setData(ClipboardData(text: value));
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(
                          content: Text('$label copied'),
                          duration: const Duration(seconds: 1),
                          behavior: SnackBarBehavior.floating,
                        ),
                      );
                    },
                    child: const Padding(
                      padding: EdgeInsets.all(4),
                      child: Icon(
                        Icons.copy_rounded,
                        size: 18,
                        color: AppTheme.slate,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ImportModelSheet extends StatefulWidget {
  const _ImportModelSheet();

  @override
  State<_ImportModelSheet> createState() => _ImportModelSheetState();
}

class _ImportModelSheetState extends State<_ImportModelSheet> {
  final HomeController controller = Get.find<HomeController>();
  final TextEditingController nameController = TextEditingController();
  final TextEditingController urlController = TextEditingController();
  final TextEditingController fileNameController = TextEditingController();
  final TextEditingController sizeController = TextEditingController(text: '0');
  final TextEditingController ramController = TextEditingController();

  bool supportImage = false;
  bool supportAudio = false;
  bool supportThinking = false;
  bool supportTinyGarden = false;
  bool supportMobileActions = false;

  @override
  void dispose() {
    nameController.dispose();
    urlController.dispose();
    fileNameController.dispose();
    sizeController.dispose();
    ramController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(30)),
      ),
      padding: EdgeInsets.fromLTRB(
        20,
        16,
        20,
        20 + MediaQuery.of(context).viewInsets.bottom,
      ),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Center(
              child: Container(
                width: 48,
                height: 4,
                decoration: BoxDecoration(
                  color: const Color(0xFFD5DBEA),
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ),
            const SizedBox(height: 20),
            Text(
              'Add custom model',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            Text(
              'Register a downloadable URL model or import a file already on this device.',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 20),
            OutlinedButton.icon(
              onPressed: () async {
                Navigator.of(context).pop();
                await controller.importLocalModel();
              },
              icon: const Icon(Icons.folder_open_rounded),
              label: const Text('Import from device storage'),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: nameController,
              decoration: const InputDecoration(labelText: 'Model name'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: urlController,
              decoration: const InputDecoration(labelText: 'Model URL'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: fileNameController,
              decoration: const InputDecoration(
                labelText: 'Download file name',
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                Expanded(
                  child: TextField(
                    controller: sizeController,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Size in bytes',
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: ramController,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Min RAM (GB)',
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                FilterChip(
                  label: const Text('Image'),
                  selected: supportImage,
                  onSelected: (value) => setState(() => supportImage = value),
                ),
                FilterChip(
                  label: const Text('Voice'),
                  selected: supportAudio,
                  onSelected: (value) => setState(() => supportAudio = value),
                ),
                FilterChip(
                  label: const Text('Thinking'),
                  selected: supportThinking,
                  onSelected: (value) =>
                      setState(() => supportThinking = value),
                ),
                FilterChip(
                  label: const Text('Tiny Garden'),
                  selected: supportTinyGarden,
                  onSelected: (value) =>
                      setState(() => supportTinyGarden = value),
                ),
                FilterChip(
                  label: const Text('Mobile Actions'),
                  selected: supportMobileActions,
                  onSelected: (value) =>
                      setState(() => supportMobileActions = value),
                ),
              ],
            ),
            const SizedBox(height: 22),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () async {
                  final payload = <String, dynamic>{
                    'name': nameController.text.trim(),
                    'url': urlController.text.trim(),
                    'fileName': fileNameController.text.trim().isEmpty
                        ? '${nameController.text.trim()}.task'
                        : fileNameController.text.trim(),
                    'sizeInBytes':
                        int.tryParse(sizeController.text.trim()) ?? 0,
                    'minRamGb': int.tryParse(ramController.text.trim()),
                    'defaultMaxTokens': 2048,
                    'defaultTopK': 40,
                    'defaultTopP': 0.95,
                    'defaultTemperature': 0.8,
                    'supportImage': supportImage,
                    'supportAudio': supportAudio,
                    'supportTinyGarden': supportTinyGarden,
                    'supportMobileActions': supportMobileActions,
                    'supportThinking': supportThinking,
                    'accelerators': <String>['gpu', 'cpu'],
                  };
                  await controller.registerCustomModel(payload);
                  if (context.mounted) {
                    Navigator.of(context).pop();
                  }
                },
                style: FilledButton.styleFrom(
                  backgroundColor: AppTheme.violet,
                  padding: const EdgeInsets.symmetric(vertical: 18),
                ),
                child: const Text('Add custom URL model'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String _humanBytes(int value) {
  if (value <= 0) {
    return 'Unknown size';
  }
  const units = <String>['B', 'KB', 'MB', 'GB', 'TB'];
  var size = value.toDouble();
  var unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return '${size.toStringAsFixed(unit == 0 ? 0 : 1)} ${units[unit]}';
}

String _basename(String path) {
  final normalized = path.replaceAll('\\', '/');
  return normalized.split('/').last;
}

String _formatRemaining(int remainingMs) {
  final duration = Duration(milliseconds: remainingMs);
  if (duration.inHours > 0) {
    return '${duration.inHours}h ${duration.inMinutes.remainder(60)}m left';
  }
  if (duration.inMinutes > 0) {
    return '${duration.inMinutes}m ${duration.inSeconds.remainder(60)}s left';
  }
  return '${duration.inSeconds}s left';
}

String _formatChatDate(DateTime value) {
  final hour = value.hour % 12 == 0 ? 12 : value.hour % 12;
  final minute = value.minute.toString().padLeft(2, '0');
  final suffix = value.hour >= 12 ? 'PM' : 'AM';
  return '${value.day}/${value.month}/${value.year} $hour:$minute $suffix';
}

String _curlModelsExample(String? baseUrl) {
  final url = _exampleUrl(baseUrl);
  return 'curl "$url/v1/models"';
}

String _curlChatExample(String? baseUrl) {
  final url = _exampleUrl(baseUrl);
  return '''
curl "$url/v1/chat/completions" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "your-loaded-model",
    "messages": [{"role": "user", "content": "Hello from another app"}]
  }'
''';
}

String _pythonClientExample(String? baseUrl) {
  final url = _exampleUrl(baseUrl);
  return '''
from openai import OpenAI

client = OpenAI(base_url="$url/v1", api_key="not-needed")
response = client.chat.completions.create(
    model="your-loaded-model",
    messages=[{"role": "user", "content": "Hello from desktop"}],
)
print(response.choices[0].message.content)
''';
}

String _exampleUrl(String? baseUrl) {
  final value = (baseUrl ?? '').trim();
  if (value.isEmpty) {
    return 'http://127.0.0.1:8080';
  }
  return value.endsWith('/') ? value.substring(0, value.length - 1) : value;
}
