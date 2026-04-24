import 'package:flutter/widgets.dart';
import 'package:get/get.dart';

import 'app/app.dart';
import 'app/core/native/native_bridge.dart';
import 'app/core/network/http_client.dart';
import 'app/core/storage/app_storage.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await AppStorage.init();
  Get.put<AppStorage>(AppStorage.instance, permanent: true);
  Get.put<HttpClient>(await HttpClient.create(), permanent: true);
  Get.put<NativeBridge>(NativeBridge(), permanent: true);

  runApp(const EdgeGalleryFlutterApp());
}
