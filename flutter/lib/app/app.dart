import 'package:flutter/material.dart';
import 'package:get/get.dart';

import 'bindings/app_binding.dart';
import 'core/theme/app_theme.dart';
import 'routes/app_pages.dart';

class EdgeGalleryFlutterApp extends StatelessWidget {
  const EdgeGalleryFlutterApp({super.key});

  @override
  Widget build(BuildContext context) {
    return GetMaterialApp(
      title: 'Edge Gallery Flutter',
      debugShowCheckedModeBanner: false,
      initialBinding: AppBinding(),
      getPages: AppPages.pages,
      initialRoute: AppPages.home,
      theme: AppTheme.lightTheme,
    );
  }
}
