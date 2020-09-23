// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// @dart = 2.6
import 'dart:convert';
import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:collection/collection.dart' show MapEquality;

// This script verifies that the release binaries only export the expected
// symbols.
//
// Android binaries (libflutter.so) should only export one symbol "JNI_OnLoad"
// of type "T".
//
// iOS binaries (Flutter.framework/Flutter) should only export Objective-C
// Symbols from the Flutter namespace. These are either of type
// "(__DATA,__common)" or "(__DATA,__objc_data)".

/// Takes the path to the out directory as the first argument, and the path to
/// the buildtools directory as the second argument.
///
/// If the second argument is not specified, it is assumed that it is the parent
/// of the out directory (for backwards compatibility).
void main(List<String> arguments) {
  assert(arguments.length == 2 || arguments.length == 1);
  final String outPath = arguments.first;
  final String buildToolsPath = arguments.length == 1
      ? p.join(p.dirname(outPath), 'buildtools')
      : arguments[1];

  String platform;
  if (Platform.isLinux) {
    platform = 'linux-x64';
  } else if (Platform.isMacOS) {
    platform = 'mac-x64';
  } else {
    throw UnimplementedError('Script only support running on Linux or MacOS.');
  }
  final String nmPath = p.join(buildToolsPath, platform, 'clang', 'bin', 'llvm-nm');
  assert(Directory(outPath).existsSync());

  final Iterable<String> releaseBuilds = Directory(outPath).listSync()
      .where((FileSystemEntity entity) => entity is Directory)
      .map<String>((FileSystemEntity dir) => p.basename(dir.path))
      .where((String s) => s.contains('_release'));

  final Iterable<String> iosReleaseBuilds = releaseBuilds
      .where((String s) => s.startsWith('ios_'));
  final Iterable<String> androidReleaseBuilds = releaseBuilds
      .where((String s) => s.startsWith('android_'));

  int failures = 0;
  failures += _checkIos(outPath, nmPath, iosReleaseBuilds);
  failures += _checkAndroid(outPath, nmPath, androidReleaseBuilds);
  print('Failing checks: $failures');
  exit(failures);
}

int _checkIos(String outPath, String nmPath, Iterable<String> builds) {
  int failures = 0;
  for (String build in builds) {
    final String libFlutter = p.join(outPath, build, 'Flutter.framework', 'Flutter');
    if (!File(libFlutter).existsSync()) {
      print('SKIPPING: $libFlutter does not exist.');
      continue;
    }
    final ProcessResult nmResult = Process.runSync(nmPath, <String>['-gUm', libFlutter]);
    if (nmResult.exitCode != 0) {
      print('ERROR: failed to execute "nm -gUm $libFlutter":\n${nmResult.stderr}');
      failures++;
      continue;
    }
    final Iterable<NmEntry> unexpectedEntries = NmEntry.parse(nmResult.stdout).where((NmEntry entry) {
      return !(((entry.type == '(__DATA,__common)' || entry.type == '(__DATA,__const)') && entry.name.startsWith('_Flutter'))
          || (entry.type == '(__DATA,__objc_data)'
              && (entry.name.startsWith('_OBJC_METACLASS_\$_Flutter') || entry.name.startsWith('_OBJC_CLASS_\$_Flutter'))));
    });
    if (unexpectedEntries.isNotEmpty) {
      print('ERROR: $libFlutter exports unexpected symbols:');
      print(unexpectedEntries.fold<String>('', (String previous, NmEntry entry) {
        return '${previous == '' ? '' : '$previous\n'}     ${entry.type} ${entry.name}';
      }));
      failures++;
    } else {
      print('OK: $libFlutter');
    }
  }
  return failures;
}

int _checkAndroid(String outPath, String nmPath, Iterable<String> builds) {
  int failures = 0;
  for (String build in builds) {
    final String libFlutter = p.join(outPath, build, 'libflutter.so');
    if (!File(libFlutter).existsSync()) {
      print('SKIPPING: $libFlutter does not exist.');
      continue;
    }
    final ProcessResult nmResult = Process.runSync(nmPath, <String>['-gU', libFlutter]);
    if (nmResult.exitCode != 0) {
      print('ERROR: failed to execute "nm -gU $libFlutter":\n${nmResult.stderr}');
      failures++;
      continue;
    }
    final Iterable<NmEntry> entries = NmEntry.parse(nmResult.stdout);
    final Map<String, String> entryMap = Map<String, String>.fromIterable(
        entries,
        key: (dynamic entry) => entry.name,
        value: (dynamic entry) => entry.type);
    final Map<String, String> expectedSymbols = <String, String>{
      'JNI_OnLoad': 'T',
      '_binary_icudtl_dat_size': 'A',
      '_binary_icudtl_dat_start': 'D',
      // TODO(fxb/47943): Remove these once Clang lld does not expose them.
      // arm
      '__adddf3': 'T',
      '__addsf3': 'T',
      '__aeabi_cdcmpeq': 'T',
      '__aeabi_cdcmple': 'T',
      '__aeabi_cdrcmple': 'T',
      '__aeabi_d2lz': 'T',
      '__aeabi_d2uiz': 'T',
      '__aeabi_d2ulz': 'T',
      '__aeabi_dadd': 'T',
      '__aeabi_dcmpeq': 'T',
      '__aeabi_dcmpge': 'T',
      '__aeabi_dcmpgt': 'T',
      '__aeabi_dcmple': 'T',
      '__aeabi_dcmplt': 'T',
      '__aeabi_ddiv': 'T',
      '__aeabi_dmul': 'T',
      '__aeabi_drsub': 'T',
      '__aeabi_dsub': 'T',
      '__aeabi_f2d': 'T',
      '__aeabi_fadd': 'T',
      '__aeabi_frsub': 'T',
      '__aeabi_fsub': 'T',
      '__aeabi_i2d': 'T',
      '__aeabi_i2f': 'T',
      '__aeabi_l2d': 'T',
      '__aeabi_l2f': 'T',
      '__aeabi_lasr': 'T',
      '__aeabi_ldivmod': 'T',
      '__aeabi_llsl': 'T',
      '__aeabi_llsr': 'T',
      '__aeabi_ui2d': 'T',
      '__aeabi_ui2f': 'T',
      '__aeabi_uidiv': 'T',
      '__aeabi_uidivmod': 'T',
      '__aeabi_ul2d': 'T',
      '__aeabi_ul2f': 'T',
      '__aeabi_uldivmod': 'T',
      '__ashldi3': 'T',
      '__ashrdi3': 'T',
      '__cmpdf2': 'T',
      '__divdf3': 'T',
      '__divdi3': 'T',
      '__eqdf2': 'T',
      '__extendsfdf2': 'T',
      '__fixdfdi': 'T',
      '__fixunsdfdi': 'T',
      '__fixunsdfsi': 'T',
      '__floatdidf': 'T',
      '__floatdisf': 'T',
      '__floatsidf': 'T',
      '__floatsisf': 'T',
      '__floatundidf': 'T',
      '__floatundisf': 'T',
      '__floatunsidf': 'T',
      '__floatunsisf': 'T',
      '__gedf2': 'T',
      '__gnu_ldivmod_helper': 'T',
      '__gnu_uldivmod_helper': 'T',
      '__gtdf2': 'T',
      '__ledf2': 'T',
      '__lshrdi3': 'T',
      '__ltdf2': 'T',
      '__muldf3': 'T',
      '__nedf2': 'T',
      '__subdf3': 'T',
      '__subsf3': 'T',
      '__udivdi3': 'T',
      '__udivsi3': 'T',
      // arm64
      '__clz_tab': 'R',
      '__udivti3': 'T',
      // arm64 && x64
      '__emutls_get_address': 'T',
      '__emutls_register_common': 'T',
      // jit x86
      '__moddi3': 'T',
      '__umoddi3': 'T',
    };
    final Map<String, String> badSymbols = <String, String>{};
    for (final String key in entryMap.keys) {
      if (entryMap[key] != expectedSymbols[key]) {
        badSymbols[key] = entryMap[key];
      }
    }
    if (badSymbols.isNotEmpty) {
      print('ERROR: $libFlutter exports the wrong symbols');
      print(' Expected $expectedSymbols');
      print(' Library has $entryMap.');
      failures++;
    } else {
      print('OK: $libFlutter');
    }
  }
  return failures;
}

class NmEntry {
  NmEntry._(this.address, this.type, this.name);

  final String address;
  final String type;
  final String name;

  static Iterable<NmEntry> parse(String stdout) {
    return LineSplitter.split(stdout).map((String line) {
      final List<String> parts = line.split(' ');
      return NmEntry._(parts[0], parts[1], parts.last);
    });
  }
}
