import 'package:get/get.dart';

import '../views/home_view.dart';

class AppPages {
  static const String home = '/';

  static final List<GetPage<dynamic>> pages = <GetPage<dynamic>>[
    GetPage<dynamic>(name: home, page: HomeView.new),
  ];
}
