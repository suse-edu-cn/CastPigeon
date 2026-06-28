import 'package:flutter/material.dart';

import 'core/bridge/api_factory.dart';
import 'core/bridge/cast_pigeon_api.dart';
import 'ui/cast_pigeon_home.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(CastPigeonApp(api: createCastPigeonApi()));
}

class CastPigeonApp extends StatelessWidget {
  const CastPigeonApp({super.key, required this.api});

  final CastPigeonApi api;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CastPigeon',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xff156f5b),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: CastPigeonHome(api: api),
    );
  }
}
